package com.stellaris.service.kafka;

import com.alibaba.fastjson.JSON;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.redis.RedisCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOrderCreatePublisherTest {
    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, Object, Object> streamOperations;
    private CreateOrderSend createOrderSend;
    private SimpleMeterRegistry meterRegistry;
    private RedisOrderCreatePublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisCache redisCache = mock(RedisCache.class);
        redisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        createOrderSend = mock(CreateOrderSend.class);
        meterRegistry = new SimpleMeterRegistry();
        RedisOrderRelayProperties properties = new RedisOrderRelayProperties();
        properties.setInflightLimit(4);
        properties.setAckQueueCapacity(4);
        properties.setAckThreads(1);
        properties.setShutdownTimeoutMs(2_000);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        publisher = new RedisOrderCreatePublisher(redisCache, createOrderSend, properties, meterRegistry);
        publisher.init();
    }

    @AfterEach
    void tearDown() {
        publisher.shutdown();
    }

    @Test
    void acknowledgesRedisOnlyAfterKafkaFutureSucceeds() throws Exception {
        when(createOrderSend.sendMessage(anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(streamOperations.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(1L);
        when(streamOperations.delete(anyString(), any(RecordId.class))).thenReturn(1L);

        assertThat(publisher.tryAcquire()).isTrue();
        publisher.publish(3, "stellaris:{sale:3}:reservation:event:stream", record());
        awaitInflight(0);

        verify(streamOperations).acknowledge(anyString(), anyString(), any(RecordId.class));
        assertThat(meterRegistry.get("stellaris_order_stream_wait_seconds").tag("shard", "3").timer().count())
                .isEqualTo(1L);
        assertThat(meterRegistry.get("stellaris_order_kafka_send_seconds")
                .tags("shard", "3", "result", "success").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("stellaris_order_relay_inflight").gauge().value()).isZero();
    }

    @Test
    void failedKafkaFutureLeavesMessagePendingForRecovery() throws Exception {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(createOrderSend.sendMessage(anyString(), anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(future);

        assertThat(publisher.tryAcquire()).isTrue();
        publisher.publish(3, "stellaris:{sale:3}:reservation:event:stream", record());
        awaitInflight(0);

        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        assertThat(meterRegistry.get("stellaris_order_kafka_send_seconds")
                .tags("shard", "3", "result", "failed").timer().count()).isEqualTo(1L);
    }

    @Test
    void parsesStreamRecordTimestampWithoutAddingLuaFields() {
        assertThat(RedisOrderCreatePublisher.streamAddTime("1720000000123-7"))
                .isEqualTo(1_720_000_000_123L);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RedisOrderCreatePublisher.streamAddTime("invalid"));
    }

    private MapRecord<String, Object, Object> record() {
        OrderCreateMq message = new OrderCreateMq();
        message.setEventId(101L);
        message.setOrderNumber(202L);
        message.setProgramId(303L);
        message.setCreateOrderTime(new Date());
        return StreamRecords.newRecord()
                .in("stellaris:{sale:3}:reservation:event:stream")
                .ofMap(Map.<Object, Object>of(
                        "intentId", "reservation-1", "payload", JSON.toJSONString(message)))
                .withId(RecordId.of((System.currentTimeMillis() - 20) + "-0"));
    }

    private void awaitInflight(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline
                && meterRegistry.get("stellaris_order_relay_inflight").gauge().value() != expected) {
            Thread.sleep(10);
        }
        assertThat(meterRegistry.get("stellaris_order_relay_inflight").gauge().value())
                .isEqualTo(expected);
    }
}
