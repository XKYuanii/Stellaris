package com.stellaris.service.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/** DLT 先持久化审计后 ACK；重放由显式接口触发，避免故障风暴时无限循环。 */
@Component
@RequiredArgsConstructor
public class CreateOrderDltConsumer {
    private final OrderCreateDltService dltService;

    @KafkaListener(topics = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" +
            "${spring.kafka.topic:create_order}" + ".DLT", groupId = "${spring.application.name}-dlt-audit",
            containerFactory = "dltAuditKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        dltService.record(record);
        acknowledgment.acknowledge();
    }
}
