package com.stellaris.service.stream;

import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.domain.OrderReservationRedisKeys;
import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.entity.OrderStreamFailure;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderStreamFailureMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.OrderReservationReleaseService;
import com.stellaris.service.reference.ReservationTransitionConflictException;
import com.stellaris.service.trade.TradeOrderCreateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises consumer death, PEL claim, materialization and atomic XACK+XDEL on Redis 7. */
@Testcontainers
class OrderStreamDeliveryIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void claimsAnAbandonedPendingRecordAndAtomicallyRemovesItAfterSuccess() throws Exception {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        OrderStreamProperties properties = new OrderStreamProperties();
        properties.setGroup("order-delivery-integration");
        properties.setClaimIdleMs(10);
        properties.setBatchSize(4);

        TradeOrderCreateService createService = mock(TradeOrderCreateService.class);
        OrderStreamRecordProcessor processor = new OrderStreamRecordProcessor(redisCache, properties,
                createService, mock(OrderStreamFailureService.class),
                mock(OrderReservationReleaseService.class), new SimpleMeterRegistry());
        OrderStreamGroupInitializer initializer = new OrderStreamGroupInitializer(redisCache, properties);
        OrderStreamPendingRecovery recovery = new OrderStreamPendingRecovery(redisCache, properties,
                initializer, processor, new SimpleMeterRegistry());

        int shard = 3;
        String stream = com.stellaris.domain.OrderReservationStreamKeys.stream(shard);
        initializer.ensure(shard);
        redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of("payload", "{}"))
                .withStreamKey(stream));
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(properties.getGroup(), "consumer-that-crashed"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));

        assertThat(delivered).hasSize(1);
        assertThat(redisTemplate.opsForStream().pending(stream, properties.getGroup())
                .getTotalPendingMessages()).isEqualTo(1L);
        Thread.sleep(25L);

        recovery.recover();

        verify(createService).create(any());
        assertThat(redisTemplate.opsForStream().pending(stream, properties.getGroup())
                .getTotalPendingMessages()).isZero();
        assertThat(redisTemplate.opsForStream().size(stream)).isZero();
    }

    @Test
    void acknowledgesPoisonRecordAfterDeterministicReleaseConflictIsDurablyEscalated() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        OrderStreamProperties properties = new OrderStreamProperties();
        properties.setGroup("order-delivery-conflict-integration");

        TradeOrderCreateService createService = mock(TradeOrderCreateService.class);
        doThrow(new IllegalArgumentException("invalid immutable snapshot"))
                .when(createService).create(any());
        OrderStreamFailureService failureService = mock(OrderStreamFailureService.class);
        OrderReservationReleaseService releaseService = mock(OrderReservationReleaseService.class);
        doThrow(new ReservationTransitionConflictException("OWNER_MISMATCH", "intent-conflict"))
                .when(releaseService).release(204L, "intent-conflict");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OrderStreamRecordProcessor processor = new OrderStreamRecordProcessor(redisCache, properties,
                createService, failureService, releaseService, meterRegistry);
        OrderStreamGroupInitializer initializer = new OrderStreamGroupInitializer(redisCache, properties);

        int shard = 4;
        String stream = com.stellaris.domain.OrderReservationStreamKeys.stream(shard);
        initializer.ensure(shard);
        OrderCreateEvent event = new OrderCreateEvent();
        event.setProgramId(204L);
        event.setIntentId("intent-conflict");
        RecordId id = redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "payload", JSON.toJSONString(event),
                "programId", "204",
                "intentId", "intent-conflict"
        )).withStreamKey(stream));
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(properties.getGroup(), "conflict-consumer"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));

        assertThat(delivered).hasSize(1);
        processor.process(stream, delivered.get(0));

        verify(failureService).record(eq(stream), eq(id.getValue()), any(), eq(204L),
                eq("intent-conflict"), any());
        verify(failureService).markManualReconciliation(eq(stream), eq(id.getValue()),
                any(ReservationTransitionConflictException.class));
        assertThat(redisTemplate.opsForStream().pending(stream, properties.getGroup())
                .getTotalPendingMessages()).isZero();
        assertThat(redisTemplate.opsForStream().size(stream)).isZero();
        assertThat(meterRegistry.counter("stellaris_order_stream_release_total",
                "result", "manual_reconciliation").count()).isEqualTo(1.0d);
    }

    @Test
    void malformedReplayEnvelopeCanStillReleaseAndAcknowledge() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        OrderStreamProperties properties = new OrderStreamProperties();
        properties.setGroup("order-delivery-malformed-envelope");

        OrderStreamFailureService failureService = mock(OrderStreamFailureService.class);
        OrderReservationReleaseService releaseService = mock(OrderReservationReleaseService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OrderStreamRecordProcessor processor = new OrderStreamRecordProcessor(redisCache, properties,
                mock(TradeOrderCreateService.class), failureService, releaseService, meterRegistry);
        OrderStreamGroupInitializer initializer = new OrderStreamGroupInitializer(redisCache, properties);

        int shard = 5;
        String stream = com.stellaris.domain.OrderReservationStreamKeys.stream(shard);
        initializer.ensure(shard);
        RecordId id = redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "payload", "{malformed-json",
                "programId", "205",
                "intentId", "intent-malformed"
        )).withStreamKey(stream));
        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(properties.getGroup(), "malformed-consumer"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));

        assertThat(delivered).hasSize(1);
        processor.process(stream, delivered.get(0));

        verify(failureService).record(eq(stream), eq(id.getValue()), eq("{malformed-json"),
                eq(205L), eq("intent-malformed"), any());
        verify(releaseService).release(205L, "intent-malformed");
        assertThat(redisTemplate.opsForStream().pending(stream, properties.getGroup())
                .getTotalPendingMessages()).isZero();
    }

    @Test
    void auditedReplayIsAtomicallyRefusedAfterReservationRelease() {
        long programId = 206L;
        String intentId = "intent-already-released";
        OrderStreamFailure failure = failure(programId, intentId);
        redisTemplate.opsForHash().put(OrderReservationRedisKeys.finalState(programId),
                intentId, "RELEASED");
        OrderStreamFailureMapper mapper = mock(OrderStreamFailureMapper.class);
        when(mapper.selectById(71L)).thenReturn(failure);

        assertThat(failureService(mapper).replay(71L)).isFalse();
        assertThat(redisTemplate.opsForStream().size(failure.getStreamKey())).isZero();
    }

    @Test
    void auditedReplayAtomicallyAppendsOnlyAnOwnedUnexpiredReservation() {
        long programId = 207L;
        String intentId = "intent-still-active";
        String seatId = "9001";
        OrderStreamFailure failure = failure(programId, intentId);
        redisTemplate.opsForHash().put(OrderReservationRedisKeys.reservation(programId), intentId,
                "{\"seats\":[{\"seatId\":\"9001\"}]}");
        redisTemplate.opsForHash().put(OrderReservationRedisKeys.owner(programId), seatId, intentId);
        redisTemplate.opsForZSet().add(OrderReservationRedisKeys.expiration(programId), intentId,
                System.currentTimeMillis() + 60_000L);
        OrderStreamFailureMapper mapper = mock(OrderStreamFailureMapper.class);
        when(mapper.selectById(72L)).thenReturn(failure);
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(failureService(mapper).replay(72L)).isTrue();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(failure.getStreamKey(), Range.unbounded());
        assertThat(records).singleElement().satisfies(record -> assertThat(record.getValue())
                .containsEntry("programId", String.valueOf(programId))
                .containsEntry("intentId", intentId)
                .containsEntry("payload", "{malformed-json"));
    }

    private OrderStreamFailureService failureService(OrderStreamFailureMapper mapper) {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        return new OrderStreamFailureService(mapper, mock(OrderMapper.class), mock(UidGenerator.class),
                redisCache, mock(OrderReservationReleaseService.class), new SimpleMeterRegistry());
    }

    private OrderStreamFailure failure(long programId, String intentId) {
        OrderStreamFailure failure = new OrderStreamFailure();
        failure.setId(71L);
        failure.setProgramId(programId);
        failure.setIntentId(intentId);
        failure.setPayload("{malformed-json");
        failure.setRecordStatus("RECORDED");
        failure.setReplayCount(0);
        failure.setStreamKey(OrderReservationStreamKeys.stream(OrderReservationStreamKeys.shard(programId)));
        return failure;
    }
}
