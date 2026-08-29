package com.stellaris.service.kafka;

import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.SeatReservationKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 每个销售分片一个阻塞 XREADGROUP Worker，实时消息不再依赖定时扫描。 */
@Slf4j
@Component
public class RedisOrderCreateRealtimeRelay implements SmartLifecycle {
    private final StringRedisTemplate redisTemplate;
    private final RedisOrderRelayProperties properties;
    private final RedisOrderCreateGroupInitializer groupInitializer;
    private final RedisOrderCreatePublisher publisher;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ThreadPoolExecutor workers;

    public RedisOrderCreateRealtimeRelay(RedisCache redisCache, RedisOrderRelayProperties properties,
                                         RedisOrderCreateGroupInitializer groupInitializer,
                                         RedisOrderCreatePublisher publisher) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.groupInitializer = groupInitializer;
        this.publisher = publisher;
    }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) return;
        int shardCount = SeatReservationKeys.SALE_SHARD_COUNT;
        workers = new ThreadPoolExecutor(shardCount, shardCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(shardCount), namedThreads("order-stream-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        for (int shard = 0; shard < shardCount; shard++) {
            int assignedShard = shard;
            workers.execute(() -> consumeShard(assignedShard));
        }
        log.info("Redis Stream 实时创建订单 Relay 已启动 workers:{} group:{} blockMs:{} inflightLimit:{}",
                shardCount, properties.getGroup(), properties.getBlockMs(), properties.getInflightLimit());
    }

    private void consumeShard(int shard) {
        String stream = SeatReservationKeys.eventStream(shard);
        Consumer consumer = Consumer.from(properties.getGroup(), "program-" + instanceId + "-shard-" + shard);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                groupInitializer.ensureGroup(shard);
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        consumer,
                        StreamReadOptions.empty().count(properties.getBatchSize())
                                .block(Duration.ofMillis(properties.getBlockMs())),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    publisher.acquire();
                    try {
                        publisher.publish(shard, stream, record);
                    } catch (RuntimeException ex) {
                        publisher.releaseUnusedPermit();
                        throw ex;
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                if (groupInitializer.isNoGroup(ex)) groupInitializer.invalidate(shard);
                if (running.get()) {
                    log.warn("Redis Stream 实时 Worker 异常，稍后重试 shard:{} stream:{}", shard, stream, ex);
                    pause(properties.getWorkerErrorBackoffMs());
                }
            }
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        ThreadPoolExecutor current = workers;
        if (current == null) return;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(properties.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("Redis Stream 实时 Worker 未在截止时间内完全退出");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            workers = null;
        }
        log.info("Redis Stream 实时创建订单 Relay 已停止，未完成消息保留在 PEL");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
