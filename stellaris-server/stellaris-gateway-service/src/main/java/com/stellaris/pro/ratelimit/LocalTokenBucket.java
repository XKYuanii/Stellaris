package com.stellaris.pro.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Redis 故障时使用的进程内降级令牌桶，不宣称全局精确。 */
final class LocalTokenBucket {
    private static final int MAX_STATES = 10_000;
    private static final long IDLE_EVICT_MILLIS = 10 * 60 * 1000L;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong checks = new AtomicLong();

    RateLimitResult check(String bucketKey, RateLimitRule rule, String dimensionValue) {
        long now = System.currentTimeMillis();
        if ((checks.incrementAndGet() & 255) == 0 || (!states.containsKey(bucketKey) && states.size() >= MAX_STATES)) {
            evict(now);
        }
        if (!states.containsKey(bucketKey) && states.size() >= MAX_STATES) {
            // Redis 故障期间宁可共享一个溢出桶，也不能让任意维度值撑爆网关堆。
            bucketKey = "overflow:" + rule.getId();
        }
        State state = states.computeIfAbsent(bucketKey, ignored -> new State(rule.getCapacity(), System.currentTimeMillis()));
        synchronized (state) {
            state.lastAccessMillis = now;
            long periods = Math.max(0, (now - state.lastRefillMillis) / rule.getRefillPeriodMillis());
            if (periods > 0) {
                state.tokens = Math.min(rule.getCapacity(), state.tokens + periods * rule.getRefillTokens());
                state.lastRefillMillis += periods * rule.getRefillPeriodMillis();
            }
            if (state.tokens > 0) {
                state.tokens--;
                return RateLimitResult.allowed(state.tokens, rule.getId(), rule.getDimension(), true);
            }
            return RateLimitResult.rejected(0, rule.getRefillPeriodMillis(), rule.getId(), rule.getDimension(), true);
        }
    }

    private void evict(long now) {
        states.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis > IDLE_EVICT_MILLIS);
        if (states.size() <= MAX_STATES) return;
        int remove = states.size() - (MAX_STATES * 9 / 10);
        for (String key : states.keySet()) {
            if (remove-- <= 0) break;
            states.remove(key);
        }
    }

    private static final class State {
        private long tokens;
        private long lastRefillMillis;
        private volatile long lastAccessMillis;

        private State(long tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
            this.lastAccessMillis = lastRefillMillis;
        }
    }
}
