package com.stellaris.service.stream;

import com.alibaba.fastjson.JSON;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.entity.OrderRequest;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.OrderReservationReleaseService;
import com.stellaris.service.reference.ReservationTransitionConflictException;
import com.stellaris.service.trade.OrderReservationRejectedException;
import com.stellaris.service.trade.TradeOrderCreateService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 一条 Stream 消息形成稳定数据库结果后才 ACK。 */
@Component
public class OrderStreamRecordProcessor {
    private static final DefaultRedisScript<Long> ACK_AND_DELETE = new DefaultRedisScript<>("""
            local acknowledged = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acknowledged > 0 then
                redis.call('XDEL', KEYS[1], ARGV[2])
            end
            return acknowledged
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final OrderStreamProperties properties;
    private final TradeOrderCreateService createService;
    private final OrderStreamFailureService failureService;
    private final OrderReservationReleaseService releaseService;
    private final MeterRegistry meterRegistry;

    public OrderStreamRecordProcessor(RedisCache redisCache, OrderStreamProperties properties,
                                      TradeOrderCreateService createService,
                                      OrderStreamFailureService failureService,
                                      OrderReservationReleaseService releaseService,
                                      MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.createService = createService;
        this.failureService = failureService;
        this.releaseService = releaseService;
        this.meterRegistry = meterRegistry;
    }

    public void process(String stream, MapRecord<String, Object, Object> record) {
        String payload = null;
        OrderCreateEvent message = null;
        try {
            payload = field(record, "payload");
            message = JSON.parseObject(payload, OrderCreateEvent.class);
            if (message == null) throw new IllegalArgumentException("empty order payload");
        } catch (RuntimeException malformed) {
            recordFailure(stream, record, payload, message, malformed);
            releaseOrRecordConflict(stream, record, message);
            acknowledge(stream, record.getId());
            meterRegistry.counter("stellaris_order_stream_consume_total", "result", "audited").increment();
            return;
        }

        try {
            createService.create(message);
            acknowledge(stream, record.getId());
            meterRegistry.counter("stellaris_order_stream_consume_total", "result", "created").increment();
        } catch (OrderReservationRejectedException rejected) {
            try {
                OrderRequest result = createService.recordRejected(message, rejected.getRejectCode());
                acknowledge(stream, record.getId());
                meterRegistry.counter("stellaris_order_stream_consume_total", "result",
                        "CREATED".equals(result.getResultStatus()) ? "duplicate" : "rejected").increment();
            } catch (OrderReservationRejectedException conflict) {
                recordFailure(stream, record, payload, message, conflict);
                releaseOrRecordConflict(stream, record, message);
                acknowledge(stream, record.getId());
                meterRegistry.counter("stellaris_order_stream_consume_total", "result", "audited").increment();
            }
        } catch (IllegalArgumentException poison) {
            recordFailure(stream, record, payload, message, poison);
            releaseOrRecordConflict(stream, record, message);
            acknowledge(stream, record.getId());
            meterRegistry.counter("stellaris_order_stream_consume_total", "result", "audited").increment();
        } catch (DataIntegrityViolationException poison) {
            recordFailure(stream, record, payload, message, poison);
            releaseOrRecordConflict(stream, record, message);
            acknowledge(stream, record.getId());
            meterRegistry.counter("stellaris_order_stream_consume_total", "result", "audited").increment();
        }
    }

    /**
     * The producer duplicates immutable recovery fields outside the JSON payload. If either the
     * parser or validation fails, release must succeed before XACK/XDEL; an unavailable Program
     * service therefore leaves the message in the PEL for retry instead of leaking the seats.
     */
    private void releaseBeforeAcknowledge(MapRecord<String, Object, Object> record, OrderCreateEvent message) {
        String rawIntentId = recoveryIntentId(record, message);
        Long programId = recoveryProgramId(record, message);
        if (programId == null || programId <= 0 || rawIntentId == null || rawIntentId.isBlank()) {
            throw new IllegalStateException("poison order record has no safe reservation recovery envelope");
        }
        releaseService.release(programId, rawIntentId);
    }

    private void recordFailure(String stream, MapRecord<String, Object, Object> record, String payload,
                               OrderCreateEvent message, Throwable failure) {
        failureService.record(stream, record.getId().getValue(), payload,
                recoveryProgramId(record, message), recoveryIntentId(record, message), failure);
    }

    private Long recoveryProgramId(MapRecord<String, Object, Object> record, OrderCreateEvent message) {
        Long programId = message == null ? null : message.getProgramId();
        if (programId != null && programId > 0) return programId;
        String rawProgramId = optionalField(record, "programId");
        if (rawProgramId == null) return null;
        try {
            long parsed = Long.parseLong(rawProgramId);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String recoveryIntentId(MapRecord<String, Object, Object> record, OrderCreateEvent message) {
        String intentId = message == null ? null : message.getIntentId();
        if (intentId != null && !intentId.isBlank()) return intentId;
        String envelopeIntentId = optionalField(record, "intentId");
        return envelopeIntentId == null || envelopeIntentId.isBlank() ? null : envelopeIntentId;
    }

    private void releaseOrRecordConflict(String stream, MapRecord<String, Object, Object> record,
                                         OrderCreateEvent message) {
        try {
            releaseBeforeAcknowledge(record, message);
        } catch (ReservationTransitionConflictException conflict) {
            failureService.markManualReconciliation(stream, record.getId().getValue(), conflict);
            meterRegistry.counter("stellaris_order_stream_release_total",
                    "result", "manual_reconciliation").increment();
        }
    }

    private void acknowledge(String stream, RecordId id) {
        // XACK 与 XDEL 必须在 Redis 内原子执行；否则 ACK 成功、XDEL 失败的记录会永久占用 XLEN，
        // 最终让准入层误判 Stream 已达到积压阈值。
        Long acknowledged = redisTemplate.execute(ACK_AND_DELETE, List.of(stream),
                properties.getGroup(), id.getValue());
        if (acknowledged == null) {
            throw new IllegalStateException("stream ACK failed for " + stream + " id=" + id);
        }
    }

    private String field(MapRecord<String, Object, Object> record, String name) {
        Map<Object, Object> value = record.getValue();
        Object result = value.get(name);
        if (result == null) throw new IllegalArgumentException("stream field is missing: " + name);
        return String.valueOf(result);
    }

    private String optionalField(MapRecord<String, Object, Object> record, String name) {
        Object result = record.getValue().get(name);
        return result == null ? null : String.valueOf(result);
    }
}
