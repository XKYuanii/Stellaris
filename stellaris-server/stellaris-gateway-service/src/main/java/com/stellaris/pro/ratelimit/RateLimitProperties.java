package com.stellaris.pro.ratelimit;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitProperties {
    private boolean enabled;
    private List<RateLimitRule> rules = new ArrayList<>();

    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("gateway.rate-limit.enabled=true requires at least one rule");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (RateLimitRule rule : rules) {
            if (rule == null) throw new IllegalArgumentException("rate-limit rule must not be null");
            rule.validate();
            if (!ids.add(rule.getId())) {
                throw new IllegalArgumentException("duplicate rate-limit rule id: " + rule.getId());
            }
        }
    }
}
