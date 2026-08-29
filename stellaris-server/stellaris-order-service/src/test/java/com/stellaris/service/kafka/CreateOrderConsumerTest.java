package com.stellaris.service.kafka;

import com.alibaba.fastjson.JSON;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.service.OrderMqCreateResult;
import com.stellaris.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static com.stellaris.domain.OrderCreateTraceHeaders.KAFKA_SEND_START_TIME;
import static com.stellaris.domain.OrderCreateTraceHeaders.STREAM_ADD_TIME;
import static com.stellaris.domain.OrderCreateTraceHeaders.STREAM_SHARD;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateOrderConsumerTest {

    private OrderService orderService;
    private SimpleMeterRegistry meterRegistry;
    private CreateOrderConsumer consumer;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        meterRegistry = new SimpleMeterRegistry();
        consumer = new CreateOrderConsumer(orderService, meterRegistry);
        acknowledgment = mock(Acknowledgment.class);
    }

    @Test
    void acknowledgesOnlyAfterOrderCreated() {
        OrderCreateMq message = message(System.currentTimeMillis());
        when(orderService.createMq(any())).thenAnswer(ignored -> result(message));

        consumer.consumerOrderMessage(record(message), acknowledgment);

        verify(orderService).createMq(any());
        verify(acknowledgment).acknowledge();
        assertEquals(1D, meterRegistry.get("stellaris_order_create_consume_total")
                .tag("result", "success").counter().count());
        assertEquals(1L, meterRegistry.get("stellaris_order_create_seconds")
                .tag("result", "success").timer().count());
        assertEquals(1L, meterRegistry.get("stellaris_order_end_to_end_seconds")
                .tag("result", "success").timer().count());
    }

    @Test
    void rethrowsAndDoesNotAcknowledgeWhenOrderCreationFails() {
        OrderCreateMq message = message(System.currentTimeMillis());
        when(orderService.createMq(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> consumer.consumerOrderMessage(record(message), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        assertEquals(1D, meterRegistry.get("stellaris_order_create_consume_total")
                .tag("result", "failed").counter().count());
        assertEquals(1L, meterRegistry.get("stellaris_order_create_seconds")
                .tag("result", "failed").timer().count());
        assertEquals(1L, meterRegistry.get("stellaris_order_end_to_end_seconds")
                .tag("result", "failed").timer().count());
    }

    @Test
    void delayedMessageIsStillProcessed() {
        OrderCreateMq message = message(System.currentTimeMillis() - CreateOrderConsumer.MESSAGE_DELAY_TIME - 1000);
        when(orderService.createMq(any())).thenAnswer(ignored -> result(message));

        consumer.consumerOrderMessage(record(message), acknowledgment);

        verify(orderService).createMq(any());
        verify(acknowledgment).acknowledge();
        assertEquals(1D, meterRegistry.get("stellaris_order_create_delay_total").counter().count());
    }

    private ConsumerRecord<String, String> record(OrderCreateMq message) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("stellaris-create_order", 0, 1L,
                String.valueOf(message.getOrderNumber()), JSON.toJSONString(message));
        long streamAddTime = Math.min(System.currentTimeMillis(), message.getCreateOrderTime().getTime());
        record.headers().add(header(STREAM_ADD_TIME, streamAddTime));
        record.headers().add(header(KAFKA_SEND_START_TIME, streamAddTime + 1));
        record.headers().add(header(STREAM_SHARD, 0));
        return record;
    }

    private RecordHeader header(String name, long value) {
        return new RecordHeader(name, Long.toString(value).getBytes(StandardCharsets.UTF_8));
    }

    private OrderCreateMq message(long createTime) {
        OrderCreateMq message = new OrderCreateMq();
        message.setEventId(101L);
        message.setOrderNumber(202L);
        message.setProgramId(303L);
        message.setCreateOrderTime(new Date(createTime));
        return message;
    }

    private OrderMqCreateResult result(OrderCreateMq message) {
        return new OrderMqCreateResult(String.valueOf(message.getOrderNumber()), System.currentTimeMillis());
    }
}
