package com.stellaris.service.reference;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.entity.ReservationTransitionEvent;
import com.stellaris.mapper.ReservationTransitionEventMapper;
import com.stellaris.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** 支付/取消本地事务写入、提交后立即执行、失败由调度重试的 Redis 同步命令。 */
@Slf4j
@Service
public class ReservationTransitionEventService {
    private final ReservationTransitionEventMapper mapper;
    private final OrderReservationReleaseService transitionService;
    private final UidGenerator uidGenerator;
    private final MeterRegistry meterRegistry;
    private final Executor transitionExecutor;
    private final AtomicLong oldestFailedAgeSeconds = new AtomicLong();

    @Value("${reservation-transition.max-attempts:0}")
    private int maxAttempts;
    @Value("${reservation-transition.retry-backoff-ms:5000}")
    private long retryBackoffMs;
    @Value("${reservation-transition.batch-size:100}")
    private int batchSize;
    @Value("${reservation-transition.processing-timeout-ms:60000}")
    private long processingTimeoutMs;

    public ReservationTransitionEventService(ReservationTransitionEventMapper mapper,
                                             OrderReservationReleaseService transitionService,
                                             UidGenerator uidGenerator, MeterRegistry meterRegistry,
                                             @Qualifier("reservationTransitionExecutor") Executor transitionExecutor) {
        this.mapper = mapper;
        this.transitionService = transitionService;
        this.uidGenerator = uidGenerator;
        this.meterRegistry = meterRegistry;
        this.transitionExecutor = transitionExecutor;
        meterRegistry.gauge("stellaris_reservation_transition_oldest_failed_age_seconds",
                oldestFailedAgeSeconds);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReservationTransitionEvent enqueue(long orderNumber, long userId, long programId,
                                              String intentId, int targetSellStatus) {
        if (programId <= 0 || intentId == null || intentId.isBlank()) {
            throw new IllegalArgumentException("complete reservation transition identity is required");
        }
        ReservationTransitionEvent existing = mapper.selectOne(Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                .eq(ReservationTransitionEvent::getOrderNumber, orderNumber)
                .eq(ReservationTransitionEvent::getUserId, userId));
        if (existing != null) return validateExisting(existing, programId, intentId, targetSellStatus);
        Date now = DateUtils.now();
        ReservationTransitionEvent event = new ReservationTransitionEvent();
        event.setId(uidGenerator.getUid());
        event.setCommandId(uidGenerator.getUid());
        event.setOrderNumber(orderNumber);
        event.setUserId(userId);
        event.setProgramId(programId);
        event.setIntentId(intentId);
        event.setTargetSellStatus(targetSellStatus);
        // Identity also lives in typed columns; payload remains diagnostic and backward-compatible.
        event.setPayload("{\"programId\":" + programId + ",\"intentId\":\""
                + intentId.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"sellStatus\":" + targetSellStatus + "}");
        event.setEventStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryTime(now);
        event.setCreateTime(now);
        event.setEditTime(now);
        event.setStatus(1);
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException duplicate) {
            ReservationTransitionEvent raced = mapper.selectOne(Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                    .eq(ReservationTransitionEvent::getOrderNumber, orderNumber)
                    .eq(ReservationTransitionEvent::getUserId, userId));
            if (raced == null) {
                throw duplicate;
            }
            return validateExisting(raced, programId, intentId, targetSellStatus);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch(event); }
            });
        } else dispatch(event);
        return event;
    }

    private ReservationTransitionEvent validateExisting(ReservationTransitionEvent existing, long programId,
                                                        String intentId, int targetSellStatus) {
        if (!Objects.equals(existing.getProgramId(), programId)
                || !Objects.equals(existing.getIntentId(), intentId)
                || !Objects.equals(existing.getTargetSellStatus(), targetSellStatus)) {
            throw new IllegalStateException("Order already has a different reservation transition command: "
                    + existing.getOrderNumber());
        }
        return existing;
    }

    @Scheduled(fixedDelayString = "${reservation-transition.fixed-delay-ms:5000}")
    public void relay() {
        recoverTimedOutProcessing();
        List<ReservationTransitionEvent> events = mapper.selectList(Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                .in(ReservationTransitionEvent::getEventStatus, "PENDING", "FAILED")
                .le(ReservationTransitionEvent::getNextRetryTime, DateUtils.now())
                .orderByAsc(ReservationTransitionEvent::getNextRetryTime)
                .orderByAsc(ReservationTransitionEvent::getId)
                .last("LIMIT " + Math.max(1, Math.min(batchSize, 500))));
        events.forEach(this::dispatch);
        refreshOldestFailureAge();
    }

    /**
     * 进程在 CAS 认领后退出时，PROCESSING 不能永久悬挂。远端同步使用同一 commandId/intentId
     * 幂等执行，因此 lease 超时后回到 FAILED 再投递是安全的。
     */
    private void recoverTimedOutProcessing() {
        Date staleBefore = new Date(System.currentTimeMillis() - Math.max(processingTimeoutMs, 1000));
        List<ReservationTransitionEvent> stuck = mapper.selectList(Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                .eq(ReservationTransitionEvent::getEventStatus, "PROCESSING")
                .le(ReservationTransitionEvent::getEditTime, staleBefore)
                .orderByAsc(ReservationTransitionEvent::getEditTime)
                .last("LIMIT " + Math.max(1, Math.min(batchSize, 500))));
        for (ReservationTransitionEvent event : stuck) {
            ReservationTransitionEvent reset = new ReservationTransitionEvent();
            reset.setEventStatus("FAILED");
            reset.setNextRetryTime(DateUtils.now());
            reset.setLastError("processing lease expired; retry with the same commandId");
            reset.setEditTime(DateUtils.now());
            mapper.update(reset, Wrappers.lambdaUpdate(ReservationTransitionEvent.class)
                    .eq(ReservationTransitionEvent::getId, event.getId())
                    .eq(ReservationTransitionEvent::getOrderNumber, event.getOrderNumber())
                    .eq(ReservationTransitionEvent::getUserId, event.getUserId())
                    .eq(ReservationTransitionEvent::getEventStatus, "PROCESSING")
                    .le(ReservationTransitionEvent::getEditTime, staleBefore));
        }
    }

    public boolean replay(long orderNumber, long userId) {
        ReservationTransitionEvent event = mapper.selectOne(Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                .eq(ReservationTransitionEvent::getOrderNumber, orderNumber)
                .eq(ReservationTransitionEvent::getUserId, userId));
        if (event == null) return false;
        ReservationTransitionEvent reset = new ReservationTransitionEvent();
        reset.setEventStatus("PENDING");
        reset.setRetryCount(0);
        reset.setNextRetryTime(DateUtils.now());
        reset.setEditTime(DateUtils.now());
        int updated = mapper.update(reset, Wrappers.lambdaUpdate(ReservationTransitionEvent.class)
                .eq(ReservationTransitionEvent::getId, event.getId())
                .eq(ReservationTransitionEvent::getOrderNumber, orderNumber)
                .eq(ReservationTransitionEvent::getUserId, userId)
                .in(ReservationTransitionEvent::getEventStatus, "FAILED", "DEAD"));
        if (updated != 1) return false;
        event.setEventStatus("PENDING");
        event.setRetryCount(0);
        return process(event);
    }

    private boolean process(ReservationTransitionEvent event) {
        Date now = DateUtils.now();
        ReservationTransitionEvent claim = new ReservationTransitionEvent();
        claim.setEventStatus("PROCESSING");
        claim.setLastAttemptTime(now);
        claim.setEditTime(now);
        int claimed = mapper.update(claim, Wrappers.lambdaUpdate(ReservationTransitionEvent.class)
                .eq(ReservationTransitionEvent::getId, event.getId())
                .eq(ReservationTransitionEvent::getOrderNumber, event.getOrderNumber())
                .eq(ReservationTransitionEvent::getUserId, event.getUserId())
                .in(ReservationTransitionEvent::getEventStatus, "PENDING", "FAILED"));
        if (claimed != 1) return "SUCCEEDED".equals(event.getEventStatus());
        try {
            transitionService.transition(event.getProgramId(), event.getIntentId(), event.getTargetSellStatus());
            ReservationTransitionEvent success = new ReservationTransitionEvent();
            success.setEventStatus("SUCCEEDED");
            success.setLastError("");
            success.setEditTime(DateUtils.now());
            int completed = mapper.update(success, Wrappers.lambdaUpdate(ReservationTransitionEvent.class)
                    .eq(ReservationTransitionEvent::getId, event.getId())
                    .eq(ReservationTransitionEvent::getOrderNumber, event.getOrderNumber())
                    .eq(ReservationTransitionEvent::getUserId, event.getUserId())
                    .eq(ReservationTransitionEvent::getEventStatus, "PROCESSING"));
            if (completed == 1) {
                meterRegistry.counter("stellaris_reservation_transition_total", "result", "success").increment();
                return true;
            }
            log.warn("Redis 状态已同步但本地事件 SUCCEEDED CAS 未命中 commandId:{}", event.getCommandId());
            return false;
        } catch (ReservationTransitionConflictException conflict) {
            int attempts = Objects.requireNonNullElse(event.getRetryCount(), 0) + 1;
            ReservationTransitionEvent failed = new ReservationTransitionEvent();
            failed.setEventStatus("DEAD");
            failed.setRetryCount(attempts);
            failed.setNextRetryTime(DateUtils.now());
            failed.setLastError(abbreviate(conflict.getMessage()));
            failed.setEditTime(DateUtils.now());
            int recorded = mapper.update(failed, Wrappers.lambdaUpdate(ReservationTransitionEvent.class)
                    .eq(ReservationTransitionEvent::getId, event.getId())
                    .eq(ReservationTransitionEvent::getOrderNumber, event.getOrderNumber())
                    .eq(ReservationTransitionEvent::getUserId, event.getUserId())
                    .eq(ReservationTransitionEvent::getEventStatus, "PROCESSING"));
            if (recorded == 1) {
                meterRegistry.counter("stellaris_reservation_transition_total", "result", "dead").increment();
                meterRegistry.counter("stellaris_reservation_transition_conflict_total",
                        "reason", conflict.getCode()).increment();
            }
            log.error("Redis 状态存在确定性冲突，已停止自动重试 commandId:{} orderNumber:{} code:{}",
                    event.getCommandId(), event.getOrderNumber(), conflict.getCode(), conflict);
            return false;
        } catch (RuntimeException ex) {
            int attempts = Objects.requireNonNullElse(event.getRetryCount(), 0) + 1;
            // Zero disables automatic dead-lettering: committed order transitions must converge
            // eventually unless an operator explicitly intervenes.
            boolean dead = maxAttempts > 0 && attempts >= maxAttempts;
            ReservationTransitionEvent failed = new ReservationTransitionEvent();
            failed.setEventStatus(dead ? "DEAD" : "FAILED");
            failed.setRetryCount(attempts);
            failed.setNextRetryTime(new Date(System.currentTimeMillis()
                    + retryBackoffMs * Math.min(attempts, 10)));
            failed.setLastError(abbreviate(ex.getMessage()));
            failed.setEditTime(DateUtils.now());
            int recorded = mapper.update(failed, Wrappers.lambdaUpdate(ReservationTransitionEvent.class)
                    .eq(ReservationTransitionEvent::getId, event.getId())
                    .eq(ReservationTransitionEvent::getOrderNumber, event.getOrderNumber())
                    .eq(ReservationTransitionEvent::getUserId, event.getUserId())
                    .eq(ReservationTransitionEvent::getEventStatus, "PROCESSING"));
            if (recorded == 1) {
                meterRegistry.counter("stellaris_reservation_transition_total", "result", dead ? "dead" : "failed").increment();
            }
            log.error("v5 Redis 状态同步失败 commandId:{} orderNumber:{}", event.getCommandId(), event.getOrderNumber(), ex);
            return false;
        }
    }

    private String abbreviate(String message) {
        if (message == null) return "unknown";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private void refreshOldestFailureAge() {
        try {
            ReservationTransitionEvent oldest = mapper.selectOne(
                    Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                            .eq(ReservationTransitionEvent::getEventStatus, "FAILED")
                            .orderByAsc(ReservationTransitionEvent::getCreateTime)
                            .orderByAsc(ReservationTransitionEvent::getId)
                            .last("LIMIT 1"));
            if (oldest == null) {
                oldestFailedAgeSeconds.set(0L);
                return;
            }
            Date since = oldest.getCreateTime();
            oldestFailedAgeSeconds.set(since == null ? 0L
                    : Math.max(0L, (System.currentTimeMillis() - since.getTime()) / 1000L));
        } catch (RuntimeException observationFailure) {
            log.warn("Redis 状态同步失败年龄采集失败", observationFailure);
        }
    }

    private void dispatch(ReservationTransitionEvent event) {
        try {
            transitionExecutor.execute(() -> process(event));
        } catch (RejectedExecutionException saturated) {
            // The database row is still PENDING/FAILED and the scheduled relay will submit it again.
            log.warn("Redis 状态同步线程池已满，保留事件等待下轮重试 commandId:{}", event.getCommandId());
        }
    }
}
