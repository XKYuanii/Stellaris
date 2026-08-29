package com.stellaris.pro.ratelimit;

/** 令牌桶的限流维度。 */
public enum RateLimitDimension {
    GLOBAL,
    CHANNEL,
    USER,
    PROGRAM,
    IP
}
