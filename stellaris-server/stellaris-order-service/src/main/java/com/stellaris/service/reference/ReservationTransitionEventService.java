package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.client.ProgramClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramOperateDataDto;
import com.stellaris.entity.ReservationTransitionEvent;
import com.stellaris.enums.BaseCode;
import com.stellaris.mapper.ReservationTransitionEventMapper;
import com.stellaris.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** 支付/取消本地事务写入、提交后立即执行、失败由调度重试的座位迁移命令。 */
@Slf4j
@Service
public class ReservationTransitionEventService {
    private final ReservationTransitionEventMapper mapper;
    private final ProgramClient programClient;
    private final UidGenerator uidGenerator;
    private final MeterRegistry meterRegistry;

    @Value("${reservation-transition.max-attempts:20}")
    private int maxAttempts;
    @Value("${reservation-transition.retry-backoff-ms:5000}")
    private long retryBackoffMs;
    @Value("${reservation-transition.batch-size:100}")
    private int batchSize;
    @Value("${reservation-transition.processing-timeout-ms:60000}")
    private long processingTimeoutMs;

    public ReservationTransitionEventService(ReservationTransitionEventMapper mapper, ProgramClient programClient,
                                             UidGenerator uidGenerator, MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.programClient = programClient;
        this.uidGenerator = uidGenerator;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReservationTransitionEvent enqueue(long orderNumber, long userId, ProgramOperateDataDto dto) {
        ReservationTransitionEvent existing = mapper.selectOne(Wrappers.lambdaQuery(ReservationTransitionEvent.class)
                .eq(ReservationTransitionEvent::getOrderNumber, orderNumber)
                .eq(ReservationTransitionEvent::getUserId, userId));
        if (existing != null) return validateExisting(existing, dto);
        Date now = DateUtils.now();
        ReservationTransitionEvent event = new ReservationTransitionEvent();
        event.setId(uidGenerator.getUid());
        event.setCommandId(uidGenerator.getUid());
        event.setOrderNumber(orderNumber);
        event.setUserId(userId);
        event.setProgramId(dto.getProgramId());
        event.setIntentId(dto.getIntentId());
        event.setTargetSellStatus(dto.getSellStatus());
        event.setPayload(JSON.toJSONString(dto));
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
            return validateExisting(raced, dto);
        }
        Runnable dispatch = () -> process(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else dispatch.run();
        return event;
    }

    private ReservationTransitionEvent validateExisting(ReservationTransitionEvent existing,
                                                        ProgramOperateDataDto requested) {
        if (!Objects.equals(existing.getProgramId(), requested.getProgramId())
                || !Objects.equals(existing.getIntentId(), requested.getIntentId())
                || !Objects.equals(existing.getTargetSellStatus(), requested.getSellStatus())) {
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
        events.forEach(this::process);
    }

    /**
     * 进程在 CAS 认领后退出时，PROCESSING 不能永久悬挂。远端迁移使用同一 commandId/intentId
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
            ProgramOperateDataDto dto = JSON.parseObject(event.getPayload(), ProgramOperateDataDto.class);
            ApiResponse<Boolean> response = programClient.operateReferenceReservation(dto);
            if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())
                    || !Boolean.TRUE.equals(response.getData())) {
                throw new IllegalStateException(response == null ? "null program response" : response.getMessage());
            }
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
            log.warn("节目迁移已成功但本地事件 SUCCEEDED CAS 未命中 commandId:{}", event.getCommandId());
            return false;
        } catch (RuntimeException ex) {
            int attempts = Objects.requireNonNullElse(event.getRetryCount(), 0) + 1;
            boolean dead = attempts >= maxAttempts;
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
            log.error("v5 座位迁移失败 commandId:{} orderNumber:{}", event.getCommandId(), event.getOrderNumber(), ex);
            return false;
        }
    }

    private String abbreviate(String message) {
        if (message == null) return "unknown";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
