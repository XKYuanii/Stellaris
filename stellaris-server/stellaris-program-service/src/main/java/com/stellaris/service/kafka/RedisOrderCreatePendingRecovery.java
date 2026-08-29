package com.stellaris.service.kafka;

import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.SeatReservationKeys;
import com.stellaris.servicelock.annotion.ServiceLock;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 独立处理 idle PEL；实时 Worker 永远只读取新消息。 */
@Slf4j
@Component
public class RedisOrderCreatePendingRecovery {
    private final StringRedisTemplate redisTemplate;
    private final RedisOrderRelayProperties properties;
    private final RedisOrderCreateGroupInitializer groupInitializer;
    private final RedisOrderCreatePublisher publisher;
    private final RedisOrderCreateDeadLetterService deadLetterService;
    private final MeterRegistry meterRegistry;
    private final String recoveryConsumer = "program-recovery-" + UUID.randomUUID();
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong deadGauge = new AtomicLong();

    public RedisOrderCreatePendingRecovery(RedisCache redisCache, RedisOrderRelayProperties properties,
                                           RedisOrderCreateGroupInitializer groupInitializer,
                                           RedisOrderCreatePublisher publisher,
                                           RedisOrderCreateDeadLetterService deadLetterService,
                                           MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.groupInitializer = groupInitializer;
        this.publisher = publisher;
        this.deadLetterService = deadLetterService;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("stellaris_order_stream_pending", pendingGauge);
        meterRegistry.gauge("stellaris_order_stream_dead", deadGauge);
    }

    @Scheduled(fixedDelayString = "${reference-order-stream.recovery-fixed-delay-ms:10000}")
    @ServiceLock(name = "reference-order-stream-pending-recovery", keys = {})
    public void recover() {
        if (!properties.isEnabled()) return;
        long pending = 0L;
        long dead = 0L;
        for (int shard = 0; shard < SeatReservationKeys.SALE_SHARD_COUNT; shard++) {
            String stream = SeatReservationKeys.eventStream(shard);
            try {
                groupInitializer.ensureGroup(shard);
                PendingMessagesSummary summary = redisTemplate.opsForStream().pending(stream, properties.getGroup());
                pending += summary == null ? 0L : summary.getTotalPendingMessages();
                Long deadSize = redisTemplate.opsForStream().size(SeatReservationKeys.eventDeadStream(shard));
                dead += deadSize == null ? 0L : deadSize;
                recoverShard(shard, stream);
            } catch (RuntimeException ex) {
                if (groupInitializer.isNoGroup(ex)) groupInitializer.invalidate(shard);
                meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "failed").increment();
                log.error("Redis Stream PEL 恢复分片失败 shard:{}", shard, ex);
            }
        }
        pendingGauge.set(pending);
        deadGauge.set(dead);
    }

    private void recoverShard(int shard, String stream) {
        PendingMessages messages = redisTemplate.opsForStream().pending(stream, properties.getGroup(),
                Range.unbounded(), Math.max(1, properties.getBatchSize()));
        if (messages == null || messages.isEmpty()) return;

        List<RecordId> claimIds = new ArrayList<>();
        Map<String, Long> deliveries = new HashMap<>();
        for (PendingMessage message : messages) {
            if (message.getElapsedTimeSinceLastDelivery().toMillis() < properties.getClaimIdleMs()
                    || publisher.isInFlight(stream, message.getIdAsString())) {
                continue;
            }
            if (!publisher.tryAcquire()) break;
            claimIds.add(message.getId());
            deliveries.put(message.getIdAsString(), message.getTotalDeliveryCount());
        }
        if (claimIds.isEmpty()) return;

        List<MapRecord<String, Object, Object>> claimed;
        try {
            claimed = redisTemplate.opsForStream().claim(stream, properties.getGroup(), recoveryConsumer,
                    Duration.ofMillis(properties.getClaimIdleMs()), claimIds.toArray(RecordId[]::new));
        } catch (RuntimeException ex) {
            claimIds.forEach(ignored -> publisher.releaseUnusedPermit());
            throw ex;
        }
        if (claimed == null) claimed = List.of();

        Set<String> returnedIds = new HashSet<>();
        for (MapRecord<String, Object, Object> record : claimed) {
            returnedIds.add(record.getId().getValue());
            long attempt = deliveries.getOrDefault(record.getId().getValue(), 0L) + 1;
            boolean permitHandedOff = false;
            try {
                if (attempt >= properties.getMaxAttempts()) {
                    deadLetterService.moveToDead(shard, stream, record,
                            "Kafka publish failed after " + attempt + " attempts");
                    meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "dead").increment();
                    publisher.releaseUnusedPermit();
                    permitHandedOff = true;
                } else {
                    publisher.publish(shard, stream, record);
                    permitHandedOff = true;
                    meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "replayed").increment();
                }
            } catch (RuntimeException ex) {
                if (!permitHandedOff) publisher.releaseUnusedPermit();
                meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "record_failed").increment();
                log.error("Redis Stream PEL 单条恢复失败，消息继续保留 shard:{} stream:{} id:{} attempt:{}",
                        shard, stream, record.getId(), attempt, ex);
            }
        }
        for (RecordId claimId : claimIds) {
            if (!returnedIds.contains(claimId.getValue())) publisher.releaseUnusedPermit();
        }
    }
}
