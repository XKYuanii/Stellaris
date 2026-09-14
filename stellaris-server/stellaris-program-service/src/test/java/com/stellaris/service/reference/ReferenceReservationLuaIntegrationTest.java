package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReferenceReservationLuaIntegrationTest {
    private static final long PROGRAM_ID = 8_800_091_305_000_005L;
    private static final String SEAT_ID = "930000000000009849";
    private static final String USER_ID = "920000000000000000";
    private static final String CATEGORY_ID = "900001";
    private static final String INTENT_ID = "testcontainers-release";
    private static final int SHARD = Math.floorMod(PROGRAM_ID, 16);
    private static final String PREFIX = "stellaris:{sale:" + SHARD + "}:program:" + PROGRAM_ID + ":seat:";
    private static final String EXPIRATION_MEMBER = PROGRAM_ID + "|" + INTENT_ID;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static DefaultRedisScript<String> releaseScript;
    private static DefaultRedisScript<String> reserveScript;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        releaseScript = new DefaultRedisScript<>();
        releaseScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/orderReservationRelease.lua")));
        releaseScript.setResultType(String.class);
        reserveScript = new DefaultRedisScript<>();
        reserveScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/referenceReserveSeats.lua")));
        reserveScript.setResultType(String.class);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void clearRedis() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void releasesSnowflakeIdsStoredAsJsonStringsWithoutPrecisionLoss() {
        List<String> keys = releaseKeys();
        redis.opsForHash().put(keys.get(0), SEAT_ID, "{\"rowCode\":12,\"colCode\":34}");
        redis.opsForHash().put(keys.get(1), SEAT_ID, INTENT_ID);
        redis.opsForHash().put(keys.get(2), INTENT_ID, stringIdReservation());
        redis.opsForHash().put(keys.get(5), USER_ID, "1");
        redis.opsForZSet().add(keys.get(6), INTENT_ID, 9_999_999_999_999D);
        redis.opsForZSet().add(keys.get(8), EXPIRATION_MEMBER, 9_999_999_999_999D);

        String result = redis.execute(releaseScript, keys, INTENT_ID, EXPIRATION_MEMBER);

        assertThat(result).isEqualTo("1");
        assertThat(redis.opsForHash().hasKey(keys.get(1), SEAT_ID)).isFalse();
        assertThat(redis.opsForZSet().score(keys.get(9), SEAT_ID)).isEqualTo(12_000_034D);
        assertThat(redis.opsForHash().get(keys.get(5), USER_ID)).isNull();
        assertThat(redis.opsForHash().get(keys.get(4), INTENT_ID)).isEqualTo("RELEASED");
    }

    @Test
    void admissionAtomicallyLocksSeatWritesStreamAndSupportsIdempotentReplay() {
        List<String> keys = reserveKeys();
        prepareAvailableSeat(keys);

        JSONObject first = JSON.parseObject(executeReserve(keys, 100));

        assertThat(first.getBooleanValue("success")).isTrue();
        assertThat(first.getBooleanValue("replayed")).isFalse();
        assertThat(redis.opsForHash().get(keys.get(3), SEAT_ID)).isEqualTo(INTENT_ID);
        assertThat(redis.opsForZSet().score(keys.get(12), SEAT_ID)).isNull();
        assertThat(redis.opsForStream().size(keys.get(7))).isEqualTo(1L);
        assertThat(redis.opsForHash().hasKey(keys.get(4), INTENT_ID)).isTrue();
        assertThat(redis.opsForHash().get(keys.get(6), USER_ID)).isEqualTo("1");
        assertThat(redis.opsForZSet().score(keys.get(9), INTENT_ID)).isNotNull();
        assertThat(redis.opsForZSet().score(keys.get(11), EXPIRATION_MEMBER)).isNotNull();

        JSONObject replay = JSON.parseObject(executeReserve(keys, 100));

        assertThat(replay.getBooleanValue("success")).isTrue();
        assertThat(replay.getBooleanValue("replayed")).isTrue();
        assertThat(redis.opsForStream().size(keys.get(7))).isEqualTo(1L);
        assertThat(redis.opsForHash().get(keys.get(6), USER_ID)).isEqualTo("1");
    }

    @Test
    void backlogLimitRejectsBeforeAnyInventoryMutation() {
        List<String> keys = reserveKeys();
        prepareAvailableSeat(keys);
        redis.opsForStream().add(keys.get(7), Map.of("payload", "existing"));

        JSONObject result = JSON.parseObject(executeReserve(keys, 1));

        assertThat(result.getBooleanValue("success")).isFalse();
        assertThat(result.getString("code")).isEqualTo("ORDER_BACKLOG_LIMIT");
        assertThat(redis.opsForZSet().score(keys.get(12), SEAT_ID)).isEqualTo(12_000_034D);
        assertThat(redis.opsForHash().hasKey(keys.get(3), SEAT_ID)).isFalse();
        assertThat(redis.opsForHash().hasKey(keys.get(4), INTENT_ID)).isFalse();
        assertThat(redis.opsForStream().size(keys.get(7))).isEqualTo(1L);
    }

    @Test
    void rejectsLegacyNumericSnowflakeIdsBeforeAnyMutation() {
        List<String> keys = releaseKeys();
        redis.opsForHash().put(keys.get(0), SEAT_ID, "{\"rowCode\":12,\"colCode\":34}");
        redis.opsForHash().put(keys.get(1), SEAT_ID, INTENT_ID);
        redis.opsForHash().put(keys.get(2), INTENT_ID, numericIdReservation());
        redis.opsForZSet().add(keys.get(6), INTENT_ID, 9_999_999_999_999D);
        redis.opsForZSet().add(keys.get(8), EXPIRATION_MEMBER, 9_999_999_999_999D);

        String result = redis.execute(releaseScript, keys, INTENT_ID, EXPIRATION_MEMBER);

        assertThat(result).isEqualTo("OWNER_MISMATCH");
        assertThat(redis.opsForHash().hasKey(keys.get(1), SEAT_ID)).isTrue();
        assertThat(redis.opsForHash().hasKey(keys.get(2), INTENT_ID)).isTrue();
        assertThat(redis.opsForHash().get(keys.get(4), INTENT_ID)).isNull();
    }

    @Test
    void rejectsMissingReservationWhileAnActiveExpirationIndexStillExists() {
        List<String> keys = releaseKeys();
        redis.opsForZSet().add(keys.get(6), INTENT_ID, 9_999_999_999_999D);
        redis.opsForZSet().add(keys.get(8), EXPIRATION_MEMBER, 9_999_999_999_999D);

        String result = redis.execute(releaseScript, keys, INTENT_ID, EXPIRATION_MEMBER);

        assertThat(result).isEqualTo("MISSING_RESERVATION");
        assertThat(redis.opsForZSet().score(keys.get(6), INTENT_ID)).isNotNull();
        assertThat(redis.opsForZSet().score(keys.get(8), EXPIRATION_MEMBER)).isNotNull();
    }

    private static List<String> releaseKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(PREFIX + "meta");
        keys.add(PREFIX + "owner");
        keys.add(PREFIX + "reservation");
        keys.add(PREFIX + "reservation:result");
        keys.add(PREFIX + "reservation:final");
        keys.add(PREFIX + "account-count");
        keys.add(PREFIX + "reservation:expiration");
        keys.add("stellaris:{sale:" + SHARD + "}:reservation:event:stream");
        keys.add("stellaris:{sale:" + SHARD + "}:reservation:expiration:index");
        keys.add(PREFIX + "available:" + CATEGORY_ID);
        return keys;
    }

    private static List<String> reserveKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(PREFIX + "ready");
        keys.add(PREFIX + "maintenance");
        keys.add(PREFIX + "meta");
        keys.add(PREFIX + "owner");
        keys.add(PREFIX + "reservation");
        keys.add(PREFIX + "reservation:result");
        keys.add(PREFIX + "account-count");
        keys.add("stellaris:{sale:" + SHARD + "}:reservation:event:stream");
        keys.add(PREFIX + "reservation:final");
        keys.add(PREFIX + "reservation:expiration");
        keys.add(PREFIX + "reservation:receipt:" + INTENT_ID);
        keys.add("stellaris:{sale:" + SHARD + "}:reservation:expiration:index");
        keys.add(PREFIX + "available:" + CATEGORY_ID);
        return keys;
    }

    private static void prepareAvailableSeat(List<String> keys) {
        redis.opsForValue().set(keys.get(0), "1");
        redis.opsForHash().put(keys.get(2), SEAT_ID,
                "{\"seatId\":\"" + SEAT_ID + "\",\"ticketCategoryId\":\"" + CATEGORY_ID
                        + "\",\"priceInCents\":12000,\"rowCode\":12,\"colCode\":34}");
        redis.opsForZSet().add(keys.get(12), SEAT_ID, 12_000_034D);
    }

    private static String executeReserve(List<String> keys, int maxStreamLength) {
        String seats = "[{\"seatId\":\"" + SEAT_ID + "\",\"ticketCategoryId\":\""
                + CATEGORY_ID + "\",\"priceInCents\":12000,\"ticketUserId\":\""
                + USER_ID + "\"}]";
        return redis.execute(reserveScript, keys, INTENT_ID, seats,
                "{\"requestId\":\"container-admission\"}", USER_ID, "6", "fingerprint-a",
                String.valueOf(System.currentTimeMillis() + 60_000), "86400000",
                String.valueOf(maxStreamLength), String.valueOf(PROGRAM_ID));
    }

    private static String stringIdReservation() {
        return "{\"userId\":\"" + USER_ID + "\",\"ticketCount\":1,\"seats\":[{"
                + "\"seatId\":\"" + SEAT_ID + "\",\"ticketCategoryId\":\"" + CATEGORY_ID + "\"}]}";
    }

    private static String numericIdReservation() {
        return "{\"userId\":" + USER_ID + ",\"ticketCount\":1,\"seats\":[{"
                + "\"seatId\":" + SEAT_ID + ",\"ticketCategoryId\":" + CATEGORY_ID + "}]}";
    }
}
