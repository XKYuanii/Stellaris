package com.stellaris.service.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.stellaris.entity.Order;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderRequestMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the trade schema's idempotency boundary and payment/cancel CAS on real InnoDB. */
@Testcontainers
class TradeDatabaseConcurrencyIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("stellaris_trade")
            .withUsername("stellaris")
            .withPassword("stellaris");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void createSchema() throws Exception {
        String schema = Files.readString(findProductionSchema());
        // The container already creates and selects stellaris_trade. Execute every production table
        // declaration verbatim while omitting only the CREATE DATABASE / USE preamble.
        schema = schema.substring(schema.indexOf("CREATE TABLE"));
        try (Connection connection = connection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8), "production trade schema"));
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OrderMapper.class);
        configuration.addMapper(OrderRequestMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/OrderMapper.xml"),
                new ClassPathResource("mapper/OrderRequestMapper.xml"));
        sqlSessionFactory = factory.getObject();
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM t_order_request");
            statement.executeUpdate("DELETE FROM d_order");
        }
    }

    @Test
    void idempotencyKeyIncludesProgramAndStillDeduplicatesTheSameProgram() throws SQLException {
        assertThat(insertRequest("intent-a", 101, 7, "same-request", 1001)).isEqualTo(1);
        assertThat(insertRequest("intent-b", 102, 7, "same-request", 1002)).isEqualTo(1);
        assertThat(insertRequest("intent-c", 101, 7, "same-request", 1003)).isZero();

        assertThat(count("SELECT COUNT(*) FROM t_order_request")).isEqualTo(2);
    }

    @Test
    void paymentAndCancellationRaceHasExactlyOneCasWinner() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO d_order
                (id, order_number, intent_id, program_id, user_id, program_permit_choose_seat,
                 order_status, create_order_time, expire_time, create_time, edit_time, status)
                VALUES (1, 9001, 'intent-9001', 101, 7, 1, 1, NOW(), DATE_ADD(NOW(), INTERVAL 15 MINUTE),
                        NOW(), NOW(), 1)
                """)) {
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> updates = List.of(
                    executor.submit(() -> casOrderStatus(2, ready, start)),
                    executor.submit(() -> casOrderStatus(3, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(updates.get(0).get(10, TimeUnit.SECONDS)
                    + updates.get(1).get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(singleInt("SELECT order_status FROM d_order WHERE order_number = 9001"))
                    .isIn(2, 3);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiryMapperUsesProductionBackoffColumnsAndSkipsDeferredHead() throws SQLException {
        insertOrder(11, 9011, -2);
        insertOrder(12, 9012, -1);
        Date now = new Date();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            OrderMapper mapper = session.getMapper(OrderMapper.class);
            List<Order> firstPage = mapper.selectExpiredForClose(now, 1, 1, 0, 1);
            assertThat(firstPage).extracting(Order::getOrderNumber).containsExactly(9011L);

            assertThat(mapper.deferExpiryClose(firstPage.get(0).getId(), now,
                    new Date(now.getTime() + 60_000), "dependency unavailable")).isEqualTo(1);
            List<Order> nextPage = mapper.selectExpiredForClose(now, 1, 1, 0, 1);
            assertThat(nextPage).extracting(Order::getOrderNumber).containsExactly(9012L);
        }
    }

    @Test
    void expiryQueryUsesIndexOrderWithoutFilesort() throws SQLException {
        insertOrder(21, 9021, -2);
        insertOrder(22, 9022, -1);

        String plan = queryString("""
                EXPLAIN FORMAT=JSON
                SELECT * FROM d_order
                WHERE order_status = 1
                  AND expire_time IS NOT NULL
                  AND expire_time <= NOW()
                  AND (expiry_next_retry_time IS NULL OR expiry_next_retry_time <= NOW())
                  AND status = 1
                ORDER BY expire_time, id
                LIMIT 100
                """);

        assertThat(plan).contains("idx_order_expiry");
        assertThat(plan).doesNotContain("\"using_filesort\": true");
    }

    private static int insertRequest(String intentId, long programId, long userId,
                                     String requestId, long orderNumber) throws SQLException {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(OrderRequestMapper.class).acquire(intentId, programId, userId,
                    requestId, orderNumber, "a".repeat(64));
        }
    }

    private static int casOrderStatus(int targetStatus, CountDownLatch ready,
                                      CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Order update = new Order();
            update.setOrderStatus(targetStatus);
            update.setEditTime(new Date());
            return session.getMapper(OrderMapper.class).update(update, Wrappers.lambdaUpdate(Order.class)
                    .eq(Order::getOrderNumber, 9001L)
                    .eq(Order::getOrderStatus, 1));
        }
    }

    private static void insertOrder(long id, long orderNumber, int expiredMinutes) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO d_order
                (id, order_number, intent_id, program_id, user_id, program_permit_choose_seat,
                 order_status, create_order_time, expire_time, create_time, edit_time, status)
                VALUES (?, ?, ?, 101, 7, 1, 1, NOW(), DATE_ADD(NOW(), INTERVAL ? MINUTE), NOW(), NOW(), 1)
                """)) {
            statement.setLong(1, id);
            statement.setLong(2, orderNumber);
            statement.setString(3, "intent-" + orderNumber);
            statement.setInt(4, expiredMinutes);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static Path findProductionSchema() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("sql/reliability/20260910_single_trade_stream_schema.sql");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException("cannot locate production trade schema from " + System.getProperty("user.dir"));
    }

    private static long count(String sql) throws SQLException {
        return singleInt(sql);
    }

    private static int singleInt(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
