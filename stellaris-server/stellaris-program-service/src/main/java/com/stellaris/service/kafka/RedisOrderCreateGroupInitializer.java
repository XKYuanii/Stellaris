package com.stellaris.service.kafka;

import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.SeatReservationKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 使用 XGROUP CREATE MKSTREAM 初始化分片消费组，避免在每次热循环中制造 BUSYGROUP 异常。 */
@Slf4j
@Component
public class RedisOrderCreateGroupInitializer {
    private final StringRedisTemplate redisTemplate;
    private final RedisOrderRelayProperties properties;
    private final Set<Integer> initialized = ConcurrentHashMap.newKeySet();
    private final Object[] shardLocks = new Object[SeatReservationKeys.SALE_SHARD_COUNT];

    public RedisOrderCreateGroupInitializer(RedisCache redisCache, RedisOrderRelayProperties properties) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        for (int shard = 0; shard < shardLocks.length; shard++) shardLocks[shard] = new Object();
    }

    public void ensureGroup(int shard) {
        if (initialized.contains(shard)) return;
        synchronized (shardLocks[shard]) {
            if (initialized.contains(shard)) return;
            String stream = SeatReservationKeys.eventStream(shard);
            try {
                // 不能调用 RedisConnection.execute("XGROUP", ...)：项目实际使用的
                // RedissonConnection 不支持通用 execute，会让所有 Relay Worker 永久重试。
                // StreamOperations 会调用 Redisson 已实现的 xGroupCreate，并携带 MKSTREAM。
                redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), properties.getGroup());
            } catch (RuntimeException ex) {
                if (!contains(ex, "BUSYGROUP")) throw ex;
            }
            initialized.add(shard);
            log.info("Redis Stream 创建订单消费组就绪 shard:{} stream:{} group:{}",
                    shard, stream, properties.getGroup());
        }
    }

    public void invalidate(int shard) {
        initialized.remove(shard);
    }

    public boolean isNoGroup(Throwable throwable) {
        return contains(throwable, "NOGROUP");
    }

    private boolean contains(Throwable throwable, String token) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(token)) return true;
            current = current.getCause();
        }
        return false;
    }

}
