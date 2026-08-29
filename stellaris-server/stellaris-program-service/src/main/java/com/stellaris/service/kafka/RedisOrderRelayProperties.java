package com.stellaris.service.kafka;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Redis Stream 创建订单中继的有界并发与恢复参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "reference-order-stream")
public class RedisOrderRelayProperties {
    private boolean enabled = true;
    private String group = "stellaris-order-relay";
    private int batchSize = 100;
    private long blockMs = 1000;
    private long claimIdleMs = 60000;
    private long recoveryFixedDelayMs = 10000;
    private int maxAttempts = 20;
    private int inflightLimit = 200;
    private int ackThreads = 4;
    private int ackQueueCapacity = 200;
    private long workerErrorBackoffMs = 1000;
    private long shutdownTimeoutMs = 10000;

    @PostConstruct
    void validate() {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("reference-order-stream.group is required");
        if (batchSize <= 0 || blockMs <= 0 || claimIdleMs <= 0 || recoveryFixedDelayMs <= 0
                || maxAttempts <= 0 || inflightLimit <= 0 || ackThreads <= 0 || ackQueueCapacity < inflightLimit
                || workerErrorBackoffMs <= 0 || shutdownTimeoutMs <= 0) {
            throw new IllegalArgumentException("reference-order-stream numeric settings must be positive and ack queue must cover inflight limit");
        }
    }
}
