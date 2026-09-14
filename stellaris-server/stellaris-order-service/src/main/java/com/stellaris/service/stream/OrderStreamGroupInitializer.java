package com.stellaris.service.stream;

import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.redis.RedisCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OrderStreamGroupInitializer {
    private final StringRedisTemplate redisTemplate;
    private final OrderStreamProperties properties;
    private final Set<Integer> initialized = ConcurrentHashMap.newKeySet();
    private final Object[] locks = new Object[OrderReservationStreamKeys.SALE_SHARD_COUNT];

    public OrderStreamGroupInitializer(RedisCache redisCache, OrderStreamProperties properties) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
    }

    public void ensure(int shard) {
        if (initialized.contains(shard)) return;
        synchronized (locks[shard]) {
            if (initialized.contains(shard)) return;
            String stream = OrderReservationStreamKeys.stream(shard);
            try {
                // MKSTREAM 让空分片也能创建消费组，避免首条消息到来前持续 NOGROUP。
                redisTemplate.execute((RedisCallback<String>) connection ->
                        connection.streamCommands().xGroupCreate(
                                stream.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                properties.getGroup(), ReadOffset.from("0-0"), true));
            } catch (RuntimeException ex) {
                if (!contains(ex, "BUSYGROUP")) throw ex;
            }
            initialized.add(shard);
            log.info("订单服务 Stream 消费组就绪 shard:{} group:{}", shard, properties.getGroup());
        }
    }

    public void invalidate(int shard) {
        initialized.remove(shard);
    }

    public boolean isNoGroup(Throwable throwable) {
        return contains(throwable, "NOGROUP");
    }

    private boolean contains(Throwable throwable, String token) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(token)) return true;
        }
        return false;
    }
}
