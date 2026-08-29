package com.stellaris.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** 订单服务也声明主主题和死信主题，避免启动顺序导致 topic 按 broker 默认值创建。 */
@Configuration
public class CreateOrderTopicConfiguration {

    @Bean
    public KafkaAdmin.NewTopics createOrderTopics(
            @Value("${prefix.distinction.name:stellaris}") String prefix,
            @Value("${spring.kafka.topic:create_order}") String topic,
            @Value("${kafka-topic.create-order.partitions:3}") int partitions,
            @Value("${kafka-topic.create-order.replicas:1}") short replicas,
            @Value("${kafka-topic.create-order.min-insync-replicas:1}") String minInSyncReplicas) {
        String fullTopicName = prefix + "-" + topic;
        NewTopic main = topic(fullTopicName, partitions, replicas, minInSyncReplicas);
        NewTopic deadLetter = topic(fullTopicName + ".DLT", partitions, replicas, minInSyncReplicas);
        return new KafkaAdmin.NewTopics(main, deadLetter);
    }

    @Bean
    public ApplicationRunner verifyCreateOrderTopicReplication(
            KafkaAdmin kafkaAdmin,
            @Value("${prefix.distinction.name:stellaris}") String prefix,
            @Value("${spring.kafka.topic:create_order}") String topic,
            @Value("${kafka-topic.create-order.replicas:1}") short requiredReplicas) {
        return arguments -> verifyReplication(kafkaAdmin, prefix + "-" + topic, requiredReplicas);
    }

    private NewTopic topic(String name, int partitions, short replicas, String minInSyncReplicas) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInSyncReplicas)
                .build();
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
