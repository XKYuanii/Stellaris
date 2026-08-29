package com.stellaris.service.kafka;

import com.alibaba.fastjson.JSON;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.redis.RedisCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Kafka 异步发送、ACK 后 Redis 确认及全局在途背压。 */
@Slf4j
@Component
public class RedisOrderCreatePublisher {
    private final StringRedisTemplate redisTemplate;
    private final CreateOrderSend createOrderSend;
    private final RedisOrderRelayProperties properties;
    private final MeterRegistry meterRegistry;
    private final Set<String> inFlightRecords = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlightGauge = new AtomicInteger();
    private Semaphore permits;
    private ThreadPoolExecutor ackExecutor;

    public RedisOrderCreatePublisher(RedisCache redisCache, CreateOrderSend createOrderSend,
                                     RedisOrderRelayProperties properties, MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.createOrderSend = createOrderSend;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        permits = new Semaphore(properties.getInflightLimit());
        ackExecutor = new ThreadPoolExecutor(properties.getAckThreads(), properties.getAckThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getAckQueueCapacity()),
                namedThreads("order-relay-ack-"), new ThreadPoolExecutor.AbortPolicy());
        meterRegistry.gauge("stellaris_order_relay_inflight", inFlightGauge);
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /** 仅在读到真实 Stream 消息后等待发送容量，避免空闲分片提前占满全局 permit。 */
    public void acquire() throws InterruptedException {
        permits.acquire();
    }

    public int tryAcquireUpTo(int maximum) {
        int acquired = 0;
        while (acquired < maximum && permits.tryAcquire()) {
            acquired++;
        }
        return acquired;
    }

    public void releaseUnusedPermit() {
        permits.release();
    }

    public void releaseUnusedPermits(int count) {
        if (count > 0) permits.release(count);
    }

    public boolean isInFlight(String stream, String recordId) {
        return inFlightRecords.contains(recordKey(stream, recordId));
    }

    /** 调用方必须先获取 permit；方法返回后 Kafka 发送与 Redis ACK 均异步完成。 */
    public void publish(int shard, String stream, MapRecord<String, Object, Object> record) {
        String recordKey = recordKey(stream, record.getId().getValue());
        if (!inFlightRecords.add(recordKey)) {
            releaseUnusedPermit();
            return;
        }
        inFlightGauge.incrementAndGet();
        long sendStartTime = System.currentTimeMillis();
        long streamAddTime;
        OrderCreateMq message;
        String payload;
        try {
            streamAddTime = streamAddTime(record.getId().getValue());
            recordStreamWait(shard, streamAddTime, sendStartTime);
            payload = RedisOrderCreateDeadLetterService.field(record, "payload");
            message = JSON.parseObject(payload, OrderCreateMq.class);
            if (message == null || message.getOrderNumber() == null || message.getEventId() == null) {
                throw new IllegalArgumentException("stream payload has no eventId/orderNumber");
            }
        } catch (RuntimeException ex) {
            finish(recordKey);
            meterRegistry.counter("stellaris_order_stream_relay_total", "result", "invalid_event").increment();
            log.error("Redis Stream 创建订单事件无效，消息保留在 PEL shard:{} stream:{} id:{}",
                    shard, stream, record.getId(), ex);
            return;
        }

        long sendStartNanos = System.nanoTime();
        try {
            createOrderSend.sendMessage(String.valueOf(message.getOrderNumber()), payload,
                            streamAddTime, sendStartTime, shard)
                    .whenComplete((result, error) -> {
                        long kafkaSendAckTime = System.currentTimeMillis();
                        recordKafkaSend(shard, error == null ? "success" : "failed", sendStartNanos);
                        submitCompletion(shard, stream, record, message, streamAddTime, sendStartTime,
                                kafkaSendAckTime, recordKey, error);
                    });
        } catch (RuntimeException ex) {
            recordKafkaSend(shard, "failed", sendStartNanos);
            finish(recordKey);
            meterRegistry.counter("stellaris_order_stream_relay_total", "result", "failed").increment();
            log.error("Redis Stream 创建订单事件发起 Kafka 异步发送失败 shard:{} stream:{} id:{}",
                    shard, stream, record.getId(), ex);
        }
    }

    private void submitCompletion(int shard, String stream, MapRecord<String, Object, Object> record,
                                  OrderCreateMq message, long streamAddTime, long sendStartTime,
                                  long kafkaSendAckTime, String recordKey, Throwable error) {
        try {
            ackExecutor.execute(() -> complete(shard, stream, record, message, streamAddTime, sendStartTime,
                    kafkaSendAckTime, recordKey, error));
        } catch (RejectedExecutionException rejected) {
            finish(recordKey);
            meterRegistry.counter("stellaris_order_stream_relay_total", "result", "ack_executor_rejected").increment();
            log.error("Relay ACK 执行器拒绝任务，消息保留在 PEL shard:{} stream:{} id:{}",
                    shard, stream, record.getId(), rejected);
        }
    }

    private void complete(int shard, String stream, MapRecord<String, Object, Object> record,
                          OrderCreateMq message, long streamAddTime, long sendStartTime,
                          long kafkaSendAckTime, String recordKey, Throwable error) {
        try {
            if (error != null) {
                meterRegistry.counter("stellaris_order_stream_relay_total", "result", "failed").increment();
                log.warn("Redis Stream 投递 Kafka 失败，消息保留在 PEL shard:{} stream:{} id:{} orderNumber:{}",
                        shard, stream, record.getId(), message.getOrderNumber(), error);
                return;
            }
            Long acknowledged = redisTemplate.opsForStream()
                    .acknowledge(stream, properties.getGroup(), record.getId());
            if (acknowledged == null || acknowledged != 1L) {
                meterRegistry.counter("stellaris_order_stream_relay_total", "result", "xack_missed").increment();
                log.warn("Kafka 已确认但 Redis XACK 未命中，允许 PEL 重投 stream:{} id:{} orderNumber:{}",
                        stream, record.getId(), message.getOrderNumber());
                return;
            }
            meterRegistry.counter("stellaris_order_stream_relay_total", "result", "success").increment();
            try {
                redisTemplate.opsForStream().delete(stream, record.getId());
            } catch (RuntimeException deleteFailure) {
                meterRegistry.counter("stellaris_order_stream_relay_total", "result", "xdel_failed").increment();
                log.warn("Redis XACK 已成功但 XDEL 失败，仅产生已确认记录残留 stream:{} id:{}",
                        stream, record.getId(), deleteFailure);
            }
            log.info("创建订单事件完成 Kafka ACK orderNumber:{} eventId:{} shard:{} streamId:{} "
                            + "streamAddTime:{} kafkaSendStartTime:{} kafkaSendAckTime:{}",
                    message.getOrderNumber(), message.getEventId(), shard, record.getId(),
                    streamAddTime, sendStartTime, kafkaSendAckTime);
        } finally {
            finish(recordKey);
        }
    }

    private void recordStreamWait(int shard, long streamAddTime, long sendStartTime) {
        Timer.builder("stellaris_order_stream_wait_seconds").tag("shard", String.valueOf(shard))
                .register(meterRegistry).record(Duration.ofMillis(Math.max(0L, sendStartTime - streamAddTime)));
    }

    private void recordKafkaSend(int shard, String result, long startNanos) {
        Timer.builder("stellaris_order_kafka_send_seconds").tag("shard", String.valueOf(shard))
                .tag("result", result).register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private void finish(String recordKey) {
        if (inFlightRecords.remove(recordKey)) {
            inFlightGauge.decrementAndGet();
            permits.release();
        }
    }

    static long streamAddTime(String recordId) {
        try {
            return Long.parseLong(recordId.substring(0, recordId.indexOf('-')));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid Redis Stream record id: " + recordId, ex);
        }
    }

    private String recordKey(String stream, String recordId) {
        return stream + "|" + recordId;
    }

    private ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        long deadline = System.currentTimeMillis() + properties.getShutdownTimeoutMs();
        while (inFlightGauge.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ackExecutor.shutdown();
        try {
            if (!ackExecutor.awaitTermination(properties.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                ackExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ackExecutor.shutdownNow();
        }
    }
}
