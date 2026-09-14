package com.stellaris.service.stream;

import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.redis.RedisCache;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 重领消费进程退出后留在 PEL 的记录；基础设施故障不设投递次数上限。 */
@Slf4j
@Component
public class OrderStreamPendingRecovery {
    private final StringRedisTemplate redisTemplate;
    private final OrderStreamProperties properties;
    private final OrderStreamGroupInitializer groupInitializer;
    private final OrderStreamRecordProcessor processor;
    private final MeterRegistry meterRegistry;
    private final String recoveryConsumer = "order-recovery-" + UUID.randomUUID();

    public OrderStreamPendingRecovery(RedisCache redisCache,
                                      OrderStreamProperties properties,
                                      OrderStreamGroupInitializer groupInitializer,
                                      OrderStreamRecordProcessor processor,
                                      MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.groupInitializer = groupInitializer;
        this.processor = processor;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${order-stream.recovery-fixed-delay-ms:10000}")
    public void recover() {
        if (!properties.isEnabled()) return;
        for (int shard = 0; shard < OrderReservationStreamKeys.SALE_SHARD_COUNT; shard++) {
            String stream = OrderReservationStreamKeys.stream(shard);
            try {
                groupInitializer.ensure(shard);
                recoverShard(stream);
            } catch (RuntimeException ex) {
                if (groupInitializer.isNoGroup(ex)) groupInitializer.invalidate(shard);
                meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "failed").increment();
                log.error("订单 Stream PEL 恢复失败 shard:{}", shard, ex);
            }
        }
    }

    private void recoverShard(String stream) {
        PendingMessages messages = redisTemplate.opsForStream().pending(stream, properties.getGroup(),
                Range.unbounded(), Math.max(1, properties.getBatchSize()));
        if (messages == null || messages.isEmpty()) return;

        List<RecordId> ids = new ArrayList<>();
        for (PendingMessage message : messages) {
            if (message.getElapsedTimeSinceLastDelivery().toMillis() >= properties.getClaimIdleMs()) {
                ids.add(message.getId());
            }
        }
        if (ids.isEmpty()) return;
        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                stream, properties.getGroup(), recoveryConsumer, Duration.ofMillis(properties.getClaimIdleMs()),
                ids.toArray(RecordId[]::new));
        if (claimed == null) return;
        for (MapRecord<String, Object, Object> record : claimed) {
            try {
                processor.process(stream, record);
                meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "replayed").increment();
            } catch (RuntimeException ex) {
                meterRegistry.counter("stellaris_order_stream_recovery_total", "result", "record_failed").increment();
                log.warn("订单 Stream PEL 记录仍不可处理 stream:{} id:{}", stream, record.getId(), ex);
            }
        }
    }

}
