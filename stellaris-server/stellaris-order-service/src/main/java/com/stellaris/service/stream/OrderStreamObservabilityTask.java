package com.stellaris.service.stream;

import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.redis.RedisCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects Stream health independently from PEL replay. A slow database call in recovery therefore
 * cannot freeze capacity and oldest-message gauges for later shards.
 */
@Slf4j
@Component
public class OrderStreamObservabilityTask {
    private final StringRedisTemplate redisTemplate;
    private final OrderStreamProperties properties;
    private final OrderStreamGroupInitializer groupInitializer;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong[] streamLengthGauges = gauges();
    private final AtomicLong[] shardPendingGauges = gauges();
    private final AtomicLong[] oldestMessageAgeGauges = gauges();

    public OrderStreamObservabilityTask(RedisCache redisCache,
                                        OrderStreamProperties properties,
                                        OrderStreamGroupInitializer groupInitializer,
                                        MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.groupInitializer = groupInitializer;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("stellaris_order_stream_pending", pendingGauge);
        for (int shard = 0; shard < OrderReservationStreamKeys.SALE_SHARD_COUNT; shard++) {
            String shardTag = String.valueOf(shard);
            Gauge.builder("stellaris_order_stream_length", streamLengthGauges[shard], AtomicLong::doubleValue)
                    .description("Current number of records in one order Stream shard")
                    .baseUnit("messages").tag("shard", shardTag).register(meterRegistry);
            Gauge.builder("stellaris_order_stream_capacity_ratio", streamLengthGauges[shard],
                            value -> value.doubleValue() / properties.getMaxStreamLength())
                    .description("Order Stream shard length divided by the producer admission limit")
                    .tag("shard", shardTag).register(meterRegistry);
            Gauge.builder("stellaris_order_stream_pending_by_shard", shardPendingGauges[shard],
                            AtomicLong::doubleValue)
                    .description("Pending entries in one order Stream consumer group shard")
                    .baseUnit("messages").tag("shard", shardTag).register(meterRegistry);
            Gauge.builder("stellaris_order_stream_oldest_message_age_seconds", oldestMessageAgeGauges[shard],
                            AtomicLong::doubleValue)
                    .description("Age of the oldest unmaterialized order Stream record")
                    .baseUnit("seconds").tag("shard", shardTag).register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${order-stream.observation-fixed-delay-ms:10000}",
            scheduler = "orderStreamObservationScheduler")
    public void observe() {
        if (!properties.isEnabled()) return;
        for (int shard = 0; shard < OrderReservationStreamKeys.SALE_SHARD_COUNT; shard++) {
            String stream = OrderReservationStreamKeys.stream(shard);
            try {
                groupInitializer.ensure(shard);
                refreshMetrics(shard, stream);
            } catch (RuntimeException ex) {
                if (groupInitializer.isNoGroup(ex)) groupInitializer.invalidate(shard);
                meterRegistry.counter("stellaris_order_stream_observation_total", "result", "failed").increment();
                log.warn("订单 Stream 指标采集失败 shard:{}", shard, ex);
            }
        }
        long totalPending = 0L;
        for (AtomicLong gauge : shardPendingGauges) totalPending += gauge.get();
        pendingGauge.set(totalPending);
    }

    private void refreshMetrics(int shard, String stream) {
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(stream, properties.getGroup());
        shardPendingGauges[shard].set(summary == null ? 0L : summary.getTotalPendingMessages());
        Long streamLength = redisTemplate.opsForStream().size(stream);
        streamLengthGauges[shard].set(streamLength == null ? 0L : streamLength);
        oldestMessageAgeGauges[shard].set(oldestMessageAgeSeconds(stream, System.currentTimeMillis()));
    }

    private long oldestMessageAgeSeconds(String stream, long nowMillis) {
        List<MapRecord<String, Object, Object>> oldest = redisTemplate.opsForStream().range(
                stream, Range.unbounded(), Limit.limit().count(1));
        if (oldest == null || oldest.isEmpty()) return 0L;
        return recordAgeSeconds(oldest.get(0).getId(), nowMillis);
    }

    static long recordAgeSeconds(RecordId id, long nowMillis) {
        Long timestamp = id == null ? null : id.getTimestamp();
        if (timestamp == null || timestamp >= nowMillis) return 0L;
        return (nowMillis - timestamp) / 1000L;
    }

    private static AtomicLong[] gauges() {
        AtomicLong[] values = new AtomicLong[OrderReservationStreamKeys.SALE_SHARD_COUNT];
        for (int index = 0; index < values.length; index++) values[index] = new AtomicLong();
        return values;
    }
}
