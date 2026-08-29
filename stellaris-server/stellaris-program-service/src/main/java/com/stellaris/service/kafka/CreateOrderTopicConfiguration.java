package com.stellaris.service.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** 创建订单主主题及死信主题声明。 */
@Configuration
public class CreateOrderTopicConfiguration {

    @Bean
    public KafkaAdmin.NewTopics createOrderTopics(
            KafkaTopic kafkaTopic,
            @Value("${kafka-topic.create-order.partitions:3}") int partitions,
            @Value("${kafka-topic.create-order.replicas:1}") short replicas,
            @Value("${kafka-topic.create-order.min-insync-replicas:1}") String minInSyncReplicas) {
        NewTopic main = TopicBuilder.name(kafkaTopic.fullTopicName())
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInSyncReplicas)
                .build();
        NewTopic deadLetter = TopicBuilder.name(kafkaTopic.fullTopicName() + ".DLT")
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInSyncReplicas)
                .build();
        return new KafkaAdmin.NewTopics(main, deadLetter);
    }

    @Bean
    public ApplicationRunner verifyCreateOrderTopicReplication(
            KafkaAdmin kafkaAdmin,
            KafkaTopic kafkaTopic,
            @Value("${kafka-topic.create-order.replicas:1}") short requiredReplicas) {
        return arguments -> verifyReplication(kafkaAdmin, kafkaTopic.fullTopicName(), requiredReplicas);
    }

    private void verifyReplication(KafkaAdmin kafkaAdmin, String mainTopic, short requiredReplicas) {
        String deadLetterTopic = mainTopic + ".DLT";
        for (TopicDescription description : kafkaAdmin.describeTopics(mainTopic, deadLetterTopic).values()) {
            boolean insufficient = description.partitions().stream()
                    .anyMatch(partition -> partition.replicas().size() < requiredReplicas);
            if (insufficient) {
                throw new IllegalStateException("Kafka topic " + description.name()
                        + " replication factor is lower than required " + requiredReplicas);
            }
        }
    }
}
