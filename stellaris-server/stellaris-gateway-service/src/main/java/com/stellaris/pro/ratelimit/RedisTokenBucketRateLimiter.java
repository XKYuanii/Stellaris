package com.stellaris.pro.ratelimit;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;

/** Redis Lua 令牌桶：补充、判断和扣减在一次原子调用中完成。 */
@Component
@Slf4j
public class RedisTokenBucketRateLimiter {
    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final LocalTokenBucket localTokenBucket = new LocalTokenBucket();
    private DefaultRedisScript<String> script;

    public RedisTokenBucketRateLimiter(ReactiveStringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/tokenBucket.lua")));
        script.setResultType(String.class);
    }

    public Mono<RateLimitResult> check(RateLimitRule rule, String dimensionValue) {
        rule.validate();
        String bucketKey = key(rule, dimensionValue);
        long now = System.currentTimeMillis();
        long ttlMillis = Math.max(rule.getRefillPeriodMillis(),
                ((rule.getCapacity() + rule.getRefillTokens() - 1) / rule.getRefillTokens())
                        * rule.getRefillPeriodMillis() * 2);
        List<String> arguments = List.of(Long.toString(rule.getCapacity()), Long.toString(rule.getRefillTokens()),
                Long.toString(rule.getRefillPeriodMillis()), Long.toString(now), Long.toString(ttlMillis));
        return redisTemplate.execute(script, Collections.singletonList(bucketKey), arguments)
                .single()
                .map(raw -> parse(raw, rule, false))
                .onErrorResume(exception -> {
                    meterRegistry.counter("stellaris_rate_limit_redis_failures_total", "rule", rule.getId(),
                            "policy", rule.getFailPolicy().name().toLowerCase()).increment();
                    log.warn("Redis rate limiter unavailable for rule {} (policy={})",
                            rule.getId(), rule.getFailPolicy(), exception);
                    return Mono.fromSupplier(() -> onRedisFailure(bucketKey, rule, dimensionValue));
                });
    }

    static RateLimitResult parse(String raw, RateLimitRule rule, boolean degraded) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Unexpected token bucket result: " + raw);
        }
        boolean allowed = "1".equals(fields[0]);
        long remaining = Long.parseLong(fields[1]);
        long retryAfter = Long.parseLong(fields[2]);
        return allowed ? RateLimitResult.allowed(remaining, rule.getId(), rule.getDimension(), degraded)
                : RateLimitResult.rejected(remaining, retryAfter, rule.getId(), rule.getDimension(), degraded);
    }

    private RateLimitResult onRedisFailure(String bucketKey, RateLimitRule rule, String dimensionValue) {
        return switch (rule.getFailPolicy()) {
            case LOCAL -> localTokenBucket.check(bucketKey, rule, dimensionValue);
            case OPEN -> RateLimitResult.allowed(-1, rule.getId(), rule.getDimension(), true);
            case CLOSED -> RateLimitResult.dependencyUnavailable(rule.getRefillPeriodMillis(), rule.getId(), rule.getDimension());
        };
    }

    private String key(RateLimitRule rule, String dimensionValue) {
        String route = rule.getId().replaceAll("[^A-Za-z0-9_-]", "_");
        // 脚本只有一个 key，不需要 Hash Tag。移除 {route} 后用户桶可分散到 Cluster 各槽，避免单槽热点。
        return "stellaris:rate:" + route + ":" + rule.getDimension().name().toLowerCase() + ":" + sha256(dimensionValue);
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
