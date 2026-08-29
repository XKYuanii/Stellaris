package com.stellaris.service.delaylease;

import com.alibaba.fastjson.JSON;
import com.stellaris.redis.RedisCache;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/** v5 延迟取消 pending/processing/ACK 队列。 */
@Component
public class DelayCancelLeaseQueue {
    private final RedisCache redisCache; private DefaultRedisScript<String> enqueue, claim, ack, requeue, fail;
    public DelayCancelLeaseQueue(RedisCache redisCache) { this.redisCache = redisCache; }
    @PostConstruct void init() {
        enqueue=script("lua/delayEnqueue.lua"); claim=script("lua/delayClaim.lua");
        ack=script("lua/delayAck.lua"); requeue=script("lua/delayRequeueExpired.lua");
        fail=script("lua/delayFail.lua");
    }
    public void enqueue(String taskId, String payload, long dueMillis) {
        redisCache.getInstance().execute(enqueue, List.of(
                        DelayCancelLeaseKeys.PENDING, DelayCancelLeaseKeys.PROCESSING,
                        DelayCancelLeaseKeys.PAYLOAD, DelayCancelLeaseKeys.ATTEMPTS,
                        DelayCancelLeaseKeys.DEAD),
                taskId, String.valueOf(dueMillis), payload);
    }
    public List<String> claim(long now, long leaseMillis, int max) {
        String result = (String) redisCache.getInstance().execute(claim,
                List.of(DelayCancelLeaseKeys.PENDING, DelayCancelLeaseKeys.PROCESSING),
                String.valueOf(now), String.valueOf(now + leaseMillis), String.valueOf(max));
        return parseClaimResult(result);
    }

    static List<String> parseClaimResult(String result) {
        // Redis cjson encodes an empty Lua table as {}, while a claimed batch is a JSON array.
        // Accept both shapes so an empty queue never breaks the one-second worker schedule.
        if (!StringUtils.hasText(result) || "{}".equals(result) || "[]".equals(result)) {
            return List.of();
        }
        return JSON.parseArray(result, String.class);
    }
    public String payload(String taskId) {
        Object value = redisCache.getInstance().opsForHash().get(DelayCancelLeaseKeys.PAYLOAD, taskId);
        return value == null ? null : String.valueOf(value);
    }
    public void ack(String taskId) {
        redisCache.getInstance().execute(ack, List.of(
                DelayCancelLeaseKeys.PENDING, DelayCancelLeaseKeys.PROCESSING,
                DelayCancelLeaseKeys.PAYLOAD, DelayCancelLeaseKeys.ATTEMPTS), taskId);
    }
    public String fail(String taskId, long now, int maxAttempts, long retryBackoffMs) {
        return (String) redisCache.getInstance().execute(fail, List.of(
                        DelayCancelLeaseKeys.PROCESSING, DelayCancelLeaseKeys.PENDING,
                        DelayCancelLeaseKeys.PAYLOAD, DelayCancelLeaseKeys.ATTEMPTS,
                        DelayCancelLeaseKeys.DEAD), taskId, String.valueOf(now),
                String.valueOf(maxAttempts), String.valueOf(retryBackoffMs));
    }
    public void requeueExpired(long now, int max, int maxAttempts, long retryBackoffMs) {
        redisCache.getInstance().execute(requeue, List.of(
                        DelayCancelLeaseKeys.PENDING, DelayCancelLeaseKeys.PROCESSING,
                        DelayCancelLeaseKeys.ATTEMPTS, DelayCancelLeaseKeys.DEAD),
                String.valueOf(now), String.valueOf(max), String.valueOf(maxAttempts),
                String.valueOf(retryBackoffMs));
    }
    private DefaultRedisScript<String> script(String path) { DefaultRedisScript<String> value=new DefaultRedisScript<>(); value.setScriptSource(new ResourceScriptSource(new ClassPathResource(path))); value.setResultType(String.class); return value; }
}
