package com.stellaris.pro.ratelimit;

import lombok.Data;

/** 一条网关令牌桶规则。 */
@Data
public class RateLimitRule {
    private String id;
    private String path;
    private RateLimitDimension dimension = RateLimitDimension.IP;
    private long capacity;
    private long refillTokens;
    private long refillPeriodMillis;
    private RateLimitFailPolicy failPolicy = RateLimitFailPolicy.CLOSED;
    private boolean enabled = true;

    public void validate() {
        if (isBlank(id) || isBlank(path) || dimension == null || failPolicy == null
                || capacity <= 0 || refillTokens <= 0 || refillPeriodMillis <= 0) {
            throw new IllegalArgumentException("Invalid rate-limit rule: " + id);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
