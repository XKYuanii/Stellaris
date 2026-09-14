package com.stellaris.service.stream;

import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.domain.OrderReservationRedisKeys;
import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderStreamFailure;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderStreamFailureMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.OrderReservationReleaseService;
import com.stellaris.util.DateUtils;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OrderStreamFailureService {
    private static final DefaultRedisScript<String> REPLAY_ACTIVE_RESERVATION = replayScript();
    private final OrderStreamFailureMapper mapper;
    private final OrderMapper orderMapper;
    private final UidGenerator uidGenerator;
    private final StringRedisTemplate redisTemplate;
    private final OrderReservationReleaseService releaseService;
    private final AtomicLong manualRequiredCount = new AtomicLong();

    public OrderStreamFailureService(OrderStreamFailureMapper mapper, OrderMapper orderMapper,
                                     UidGenerator uidGenerator,
                                     RedisCache redisCache, OrderReservationReleaseService releaseService,
                                     MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.uidGenerator = uidGenerator;
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.releaseService = releaseService;
        Gauge.builder("stellaris_order_stream_manual_required", manualRequiredCount, AtomicLong::get)
                .description("Number of Stream failure audits waiting for manual reservation reconciliation")
                .register(meterRegistry);
    }

    /**
     * Replays only while the original reservation is still active and unexpired. The validation and
     * XADD run in one Redis Lua operation so a concurrent release cannot create a database-only lock.
     */
    public boolean replay(long id) {
        OrderStreamFailure failure = mapper.selectById(id);
        if (failure == null || !"RECORDED".equals(failure.getRecordStatus())
                || failure.getStreamKey() == null || failure.getStreamKey().isBlank()
                || failure.getPayload() == null || failure.getPayload().isBlank()) return false;
        RecoveryIdentity identity = recoveryIdentity(failure);
        if (identity == null) return false;
        String expectedStream = OrderReservationStreamKeys.stream(
                OrderReservationStreamKeys.shard(identity.programId()));
        if (!expectedStream.equals(failure.getStreamKey())) return false;
        String replayResult = redisTemplate.execute(REPLAY_ACTIVE_RESERVATION, List.of(
                        OrderReservationRedisKeys.reservation(identity.programId()),
                        OrderReservationRedisKeys.finalState(identity.programId()),
                        OrderReservationRedisKeys.owner(identity.programId()),
                        OrderReservationRedisKeys.expiration(identity.programId()),
                        expectedStream),
                identity.intentId(), String.valueOf(identity.programId()), failure.getPayload(),
                String.valueOf(System.currentTimeMillis()));
        if (replayResult == null || !replayResult.startsWith("OK:")) return false;
        OrderStreamFailure update = new OrderStreamFailure();
        update.setRecordStatus("REPLAYED");
        update.setReplayCount((failure.getReplayCount() == null ? 0 : failure.getReplayCount()) + 1);
        update.setEditTime(DateUtils.now());
        return mapper.update(update, Wrappers.lambdaUpdate(OrderStreamFailure.class)
                .eq(OrderStreamFailure::getId, id)
                .eq(OrderStreamFailure::getRecordStatus, "RECORDED")) == 1;
    }

    /**
     * Operator-controlled, owner-validating release for a deterministic Redis conflict. This never
     * deletes another reservation's owner: the normal release Lua must succeed before the audit row
     * becomes RESOLVED.
     */
    public boolean releaseReservation(long id) {
        OrderStreamFailure failure = mapper.selectById(id);
        if (failure == null || !"MANUAL_REQUIRED".equals(failure.getRecordStatus())) return false;
        RecoveryIdentity identity = recoveryIdentity(failure);
        if (identity == null) {
            throw new IllegalStateException("stream failure has no complete reservation recovery envelope: " + id);
        }
        long activeOrders = orderMapper.selectCount(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getProgramId, identity.programId())
                .eq(Order::getIntentId, identity.intentId())
                .in(Order::getOrderStatus, OrderStatus.NO_PAY.getCode(), OrderStatus.PAY.getCode()));
        if (activeOrders > 0) {
            throw new IllegalStateException("active trade order exists for reservation intent: "
                    + identity.intentId());
        }
        releaseService.release(identity.programId(), identity.intentId());
        OrderStreamFailure update = new OrderStreamFailure();
        update.setRecordStatus("RESOLVED");
        update.setEditTime(DateUtils.now());
        int changed = mapper.update(update, Wrappers.lambdaUpdate(OrderStreamFailure.class)
                .eq(OrderStreamFailure::getId, id)
                .eq(OrderStreamFailure::getRecordStatus, "MANUAL_REQUIRED"));
        if (changed == 1) {
            refreshManualRequiredCount();
            return true;
        }
        OrderStreamFailure current = mapper.selectById(id);
        boolean resolved = current != null && "RESOLVED".equals(current.getRecordStatus());
        refreshManualRequiredCount();
        return resolved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void record(String stream, String streamId, String payload, Long recoveryProgramId,
                       String recoveryIntentId, Throwable failure) {
        if (mapper.selectCount(Wrappers.lambdaQuery(OrderStreamFailure.class)
                .eq(OrderStreamFailure::getStreamKey, stream)
                .eq(OrderStreamFailure::getStreamId, streamId)) > 0) return;
        OrderCreateEvent message = parse(payload);
        OrderStreamFailure item = new OrderStreamFailure();
        item.setId(uidGenerator.getUid());
        item.setStreamKey(stream);
        item.setStreamId(streamId);
        item.setEventId(message == null ? null : message.getEventId());
        item.setOrderNumber(message == null ? null : message.getOrderNumber());
        item.setUserId(message == null ? null : message.getUserId());
        item.setProgramId(validProgramId(recoveryProgramId) ? recoveryProgramId
                : message == null ? null : message.getProgramId());
        item.setIntentId(validIntentId(recoveryIntentId) ? recoveryIntentId
                : message == null ? null : message.getIntentId());
        item.setPayload(payload == null ? "" : payload);
        item.setExceptionMessage(abbreviate(failure == null ? "unknown" : failure.getMessage()));
        item.setRecordStatus("RECORDED");
        item.setReplayCount(0);
        item.setCreateTime(DateUtils.now());
        item.setEditTime(DateUtils.now());
        item.setStatus(1);
        try {
            mapper.insert(item);
        } catch (DuplicateKeyException duplicate) {
            if (mapper.selectCount(Wrappers.lambdaQuery(OrderStreamFailure.class)
                    .eq(OrderStreamFailure::getStreamKey, stream)
                    .eq(OrderStreamFailure::getStreamId, streamId)) == 0) throw duplicate;
        }
    }

    /**
     * Reservation Lua has already returned a deterministic state/owner conflict. Retrying the
     * Stream record cannot release the seats, so keep the durable audit row but hand it to an
     * operator instead of leaving the same record in the PEL forever.
     */
    @Transactional(rollbackFor = Exception.class)
    public void markManualReconciliation(String stream, String streamId, Throwable conflict) {
        OrderStreamFailure update = new OrderStreamFailure();
        update.setRecordStatus("MANUAL_REQUIRED");
        update.setExceptionMessage(abbreviate(conflict == null ? "reservation transition conflict"
                : conflict.getMessage()));
        update.setEditTime(DateUtils.now());
        int updated = mapper.update(update, Wrappers.lambdaUpdate(OrderStreamFailure.class)
                .eq(OrderStreamFailure::getStreamKey, stream)
                .eq(OrderStreamFailure::getStreamId, streamId));
        if (updated != 1) {
            throw new IllegalStateException("stream failure audit row is missing for " + stream
                    + " id=" + streamId);
        }
        refreshManualRequiredCount();
    }

    @Scheduled(fixedDelayString = "${order-stream.failure-observation-fixed-delay-ms:30000}",
            scheduler = "orderStreamObservationScheduler")
    public void refreshManualRequiredCount() {
        try {
            manualRequiredCount.set(mapper.selectCount(Wrappers.lambdaQuery(OrderStreamFailure.class)
                    .eq(OrderStreamFailure::getRecordStatus, "MANUAL_REQUIRED")));
        } catch (RuntimeException ignored) {
            // Preserve the last successful value. Database availability has its own health/alert path.
        }
    }

    private OrderCreateEvent parse(String payload) {
        try {
            return JSON.parseObject(payload, OrderCreateEvent.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private RecoveryIdentity recoveryIdentity(OrderStreamFailure failure) {
        Long programId = failure.getProgramId();
        String intentId = failure.getIntentId();
        if (!validProgramId(programId) || !validIntentId(intentId)) {
            OrderCreateEvent message = parse(failure.getPayload());
            if (!validProgramId(programId) && message != null) programId = message.getProgramId();
            if (!validIntentId(intentId) && message != null) intentId = message.getIntentId();
        }
        return validProgramId(programId) && validIntentId(intentId)
                ? new RecoveryIdentity(programId, intentId) : null;
    }

    private boolean validProgramId(Long programId) {
        return programId != null && programId > 0;
    }

    private boolean validIntentId(String intentId) {
        return intentId != null && !intentId.isBlank();
    }

    private String abbreviate(String value) {
        if (value == null) return "unknown";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static DefaultRedisScript<String> replayScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/orderStreamFailureReplay.lua")));
        script.setResultType(String.class);
        return script;
    }

    private record RecoveryIdentity(long programId, String intentId) {
    }
}
