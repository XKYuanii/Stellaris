package com.stellaris.service.kafka;

import com.alibaba.fastjson.JSON;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.service.OrderMqCreateResult;
import com.stellaris.service.OrderService;
import com.stellaris.util.StringUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;
import static com.stellaris.domain.OrderCreateTraceHeaders.KAFKA_SEND_START_TIME;
import static com.stellaris.domain.OrderCreateTraceHeaders.STREAM_ADD_TIME;
import static com.stellaris.domain.OrderCreateTraceHeaders.STREAM_SHARD;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: kafka 创建订单 消费
 * @author: 阿星不是程序员
 **/
@Slf4j
@AllArgsConstructor
@Component
public class CreateOrderConsumer {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public static Long MESSAGE_DELAY_TIME = 5000L;
    
    @KafkaListener(topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${spring.kafka.topic:create_order}"})
    public void consumerOrderMessage(ConsumerRecord<String,String> consumerRecord, Acknowledgment acknowledgment){
        String value = consumerRecord.value();
        if (StringUtil.isEmpty(value)) {
            acknowledgment.acknowledge();
            return;
        }
        long kafkaConsumeTime = System.currentTimeMillis();
        long createStartNanos = System.nanoTime();
        OrderCreateMq orderCreateMq = null;
        String result = "failed";
        long orderCreatedTime = 0L;
        try {
            orderCreateMq = JSON.parseObject(value, OrderCreateMq.class);
            if (orderCreateMq == null || orderCreateMq.getCreateOrderTime() == null) {
                throw new IllegalArgumentException("创建订单消息缺少创建时间");
            }
            long createOrderTimeTimestamp = orderCreateMq.getCreateOrderTime().getTime();
            
            long currentTimeTimestamp = System.currentTimeMillis();
            
            long delayTime = currentTimeTimestamp - createOrderTimeTimestamp;
            
            log.info("消费到kafka的创建订单消息 消息体: {} 延迟时间 : {} 毫秒",value,delayTime);
            
            if (currentTimeTimestamp - createOrderTimeTimestamp > MESSAGE_DELAY_TIME) {
                // Kafka 排队延迟不等于业务过期。只告警，仍继续创建订单。
                log.warn("创建订单消息排队延迟超过阈值 threshold:{} delay:{} eventId:{} orderNumber:{}",
                        MESSAGE_DELAY_TIME, delayTime, orderCreateMq.getEventId(), orderCreateMq.getOrderNumber());
                meterRegistry.counter("stellaris_order_create_delay_total").increment();
            }

            OrderMqCreateResult createResult = orderService.createMq(orderCreateMq);
            String orderNumber = createResult.orderNumber();
            orderCreatedTime = createResult.orderCreatedTime();
            acknowledgment.acknowledge();
            result = "success";
            meterRegistry.counter("stellaris_order_create_consume_total", "result", "success").increment();
            log.info("消费创建订单消息成功 eventId:{} orderNumber:{} shard:{} streamAddTime:{} "
                            + "kafkaSendStartTime:{} kafkaConsumeTime:{} orderCreatedTime:{}",
                    orderCreateMq.getEventId(), orderNumber, headerLong(consumerRecord, STREAM_SHARD),
                    headerLong(consumerRecord, STREAM_ADD_TIME), headerLong(consumerRecord, KAFKA_SEND_START_TIME),
                    kafkaConsumeTime, orderCreatedTime);
        } catch (RuntimeException e) {
            meterRegistry.counter("stellaris_order_create_consume_total", "result", "failed").increment();
            log.error("处理创建订单Kafka消息失败，将交由重试/DLT eventId:{} orderNumber:{}",
                    orderCreateMq == null ? null : orderCreateMq.getEventId(),
                    orderCreateMq == null ? null : orderCreateMq.getOrderNumber(), e);
            throw e;
        } finally {
            Timer.builder("stellaris_order_create_seconds").tag("result", result).register(meterRegistry)
                    .record(System.nanoTime() - createStartNanos, TimeUnit.NANOSECONDS);
            long streamAddTime = headerLong(consumerRecord, STREAM_ADD_TIME);
            long completedTime = orderCreatedTime > 0 ? orderCreatedTime : System.currentTimeMillis();
            if (streamAddTime > 0 && completedTime >= streamAddTime) {
                Timer.builder("stellaris_order_end_to_end_seconds").tag("result", result).register(meterRegistry)
                        .record(Duration.ofMillis(completedTime - streamAddTime));
            }
        }
    }

    private long headerLong(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) return -1L;
        try {
            return Long.parseLong(new String(header.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
