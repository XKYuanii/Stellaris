package com.stellaris.service.stream;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "order-stream")
public class OrderStreamProperties {
    private boolean enabled = true;
    private String group = "stellaris-order-service";
    private long maxStreamLength = 100000;
    private int batchSize = 32;
    private long blockMs = 1000;
    private long claimIdleMs = 60000;
    private long recoveryFixedDelayMs = 10000;
    private long errorBackoffMs = 1000;
    private long shutdownTimeoutMs = 10000;

    @PostConstruct
    void validate() {
        if (group == null || group.isBlank() || maxStreamLength <= 0 || batchSize <= 0 || blockMs <= 0 || claimIdleMs <= 0
                || recoveryFixedDelayMs <= 0 || errorBackoffMs <= 0 || shutdownTimeoutMs <= 0) {
            throw new IllegalArgumentException("order-stream settings must be positive");
        }
    }
}
