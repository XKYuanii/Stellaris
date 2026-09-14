package com.stellaris.service.stream;

import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.redis.RedisCache;
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

/** 每个销售分片一个阻塞消费者；同一分片串行写库，跨分片并行。 */
@Slf4j
@Component
public class OrderStreamRealtimeConsumer implements SmartLifecycle {
    private final StringRedisTemplate redisTemplate;
    private final OrderStreamProperties properties;
    private final OrderStreamGroupInitializer groupInitializer;
    private final OrderStreamRecordProcessor processor;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ThreadPoolExecutor workers;

    public OrderStreamRealtimeConsumer(RedisCache redisCache,
                                       OrderStreamProperties properties,
                                       OrderStreamGroupInitializer groupInitializer,
                                       OrderStreamRecordProcessor processor) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.groupInitializer = groupInitializer;
        this.processor = processor;
    }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) return;
        int shardCount = OrderReservationStreamKeys.SALE_SHARD_COUNT;
        workers = new ThreadPoolExecutor(shardCount, shardCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(shardCount), namedThreads(), new ThreadPoolExecutor.AbortPolicy());
        for (int shard = 0; shard < shardCount; shard++) {
            int assignedShard = shard;
            workers.execute(() -> consumeShard(assignedShard));
        }
        log.info("订单服务开始直消费 Redis Stream workers:{} group:{}", shardCount, properties.getGroup());
    }

    private void consumeShard(int shard) {
        String stream = OrderReservationStreamKeys.stream(shard);
        Consumer consumer = Consumer.from(properties.getGroup(), "order-" + instanceId + "-" + shard);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                groupInitializer.ensure(shard);
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        consumer,
                        StreamReadOptions.empty().count(properties.getBatchSize())
                                .block(Duration.ofMillis(properties.getBlockMs())),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records == null) continue;
                for (MapRecord<String, Object, Object> record : records) {
                    if (!running.get()) return;
                    processor.process(stream, record);
                }
            } catch (RuntimeException ex) {
                if (groupInitializer.isNoGroup(ex)) groupInitializer.invalidate(shard);
                if (running.get()) {
                    log.warn("订单 Stream 消费暂时失败，记录留在 PEL shard:{}", shard, ex);
                    pause(properties.getErrorBackoffMs());
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
                log.warn("订单 Stream Worker 未在截止时间内退出，未确认记录将由 PEL 重领");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            workers = null;
        }
    }

    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }

    private ThreadFactory namedThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "order-stream-consumer-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
