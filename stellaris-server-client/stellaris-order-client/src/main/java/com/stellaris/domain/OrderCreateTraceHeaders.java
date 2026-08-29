package com.stellaris.domain;

/** 创建订单可靠事件在 Kafka 中传递的低基数链路时间头。 */
public final class OrderCreateTraceHeaders {
    public static final String STREAM_ADD_TIME = "stellaris-stream-add-time";
    public static final String KAFKA_SEND_START_TIME = "stellaris-kafka-send-start-time";
    public static final String STREAM_SHARD = "stellaris-stream-shard";

    private OrderCreateTraceHeaders() {
    }
}
