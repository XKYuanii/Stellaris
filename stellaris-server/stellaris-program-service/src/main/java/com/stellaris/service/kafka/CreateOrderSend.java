package com.stellaris.service.kafka;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.stellaris.domain.OrderCreateTraceHeaders.KAFKA_SEND_START_TIME;
import static com.stellaris.domain.OrderCreateTraceHeaders.STREAM_ADD_TIME;
import static com.stellaris.domain.OrderCreateTraceHeaders.STREAM_SHARD;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: kafka 创建订单 发送
 * @author: 阿星不是程序员
 **/
@Slf4j
@AllArgsConstructor
@Component
public class CreateOrderSend {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private KafkaTopic kafkaTopic;
    
    
    public CompletableFuture<SendResult<String, String>> sendMessage(String key, String message) {
        log.info("创建订单kafka发送消息 key:{} 消息体:{}", key, message);
        return kafkaTemplate.send(kafkaTopic.fullTopicName(), key, message);
    }

    public CompletableFuture<SendResult<String, String>> sendMessage(String key, String message,
                                                                      long streamAddTime, long kafkaSendStartTime,
                                                                      int shard) {
        ProducerRecord<String, String> record = new ProducerRecord<>(kafkaTopic.fullTopicName(), null, null,
                key, message, List.of(
                header(STREAM_ADD_TIME, streamAddTime),
                header(KAFKA_SEND_START_TIME, kafkaSendStartTime),
                header(STREAM_SHARD, shard)));
        log.info("创建订单Kafka异步发送 orderNumber:{} shard:{} streamAddTime:{} kafkaSendStartTime:{}",
                key, shard, streamAddTime, kafkaSendStartTime);
        return kafkaTemplate.send(record);
    }

    private RecordHeader header(String name, long value) {
        return new RecordHeader(name, Long.toString(value).getBytes(StandardCharsets.UTF_8));
    }
}
