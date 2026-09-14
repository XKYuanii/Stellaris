package com.stellaris.service.reference;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.entity.PaymentReconciliationEvent;
import com.stellaris.mapper.PaymentReconciliationEventMapper;
import com.stellaris.util.DateUtils;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Durable payment reconciliation work recorded before invoking the payment service. */
@Service
@Slf4j
public class PaymentReconciliationEventService {
    private final PaymentReconciliationEventMapper mapper;
    private final UidGenerator uidGenerator;
    private final MeterRegistry meterRegistry;
    private final AtomicLong oldestFailedAgeSeconds = new AtomicLong();

    @Value("${payment-reconciliation.batch-size:100}")
    private int batchSize;
    @Value("${payment-reconciliation.retry-backoff-ms:10000}")
    private long retryBackoffMs;
    @Value("${payment-reconciliation.processing-timeout-ms:60000}")
    private long processingTimeoutMs;

    public PaymentReconciliationEventService(PaymentReconciliationEventMapper mapper, UidGenerator uidGenerator,
                                             MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.uidGenerator = uidGenerator;
        this.meterRegistry = meterRegistry;
        Gauge.builder("stellaris_payment_reconciliation_oldest_failed_age_seconds",
                        oldestFailedAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest failed payment reconciliation event")
                .register(meterRegistry);
    }

    /** REQUIRES_NEW guarantees that the evidence exists before the remote payment call begins. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void track(long orderNumber) {
        if (orderNumber <= 0) throw new IllegalArgumentException("orderNumber must be positive");
        if (mapper.selectCount(Wrappers.lambdaQuery(PaymentReconciliationEvent.class)
                .eq(PaymentReconciliationEvent::getOrderNumber, orderNumber)) > 0) return;
        Date now = DateUtils.now();
        PaymentReconciliationEvent event = new PaymentReconciliationEvent();
        event.setId(uidGenerator.getUid());
        event.setOrderNumber(orderNumber);
        event.setEventStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryTime(now);
        event.setCreateTime(now);
        event.setEditTime(now);
        event.setStatus(1);
        try {
            mapper.insert(event);
        } catch (DuplicateKeyException duplicate) {
            if (mapper.selectCount(Wrappers.lambdaQuery(PaymentReconciliationEvent.class)
                    .eq(PaymentReconciliationEvent::getOrderNumber, orderNumber)) == 0) throw duplicate;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public List<PaymentReconciliationEvent> claimDue() {
        recoverTimedOut();
        refreshOldestFailureAge();
        List<PaymentReconciliationEvent> due = mapper.selectList(Wrappers.lambdaQuery(PaymentReconciliationEvent.class)
                .in(PaymentReconciliationEvent::getEventStatus, "PENDING", "WAITING", "FAILED")
                .le(PaymentReconciliationEvent::getNextRetryTime, DateUtils.now())
                .orderByAsc(PaymentReconciliationEvent::getNextRetryTime)
                .orderByAsc(PaymentReconciliationEvent::getId)
                .last("LIMIT " + Math.max(1, Math.min(batchSize, 500))));
        List<PaymentReconciliationEvent> claimed = new ArrayList<>(due.size());
        for (PaymentReconciliationEvent event : due) {
            Date now = DateUtils.now();
            PaymentReconciliationEvent update = new PaymentReconciliationEvent();
            update.setEventStatus("PROCESSING");
            update.setLastAttemptTime(now);
            update.setEditTime(now);
            int changed = mapper.update(update, Wrappers.lambdaUpdate(PaymentReconciliationEvent.class)
                    .eq(PaymentReconciliationEvent::getId, event.getId())
                    .eq(PaymentReconciliationEvent::getOrderNumber, event.getOrderNumber())
                    .in(PaymentReconciliationEvent::getEventStatus, "PENDING", "WAITING", "FAILED"));
            if (changed == 1) {
                event.setEventStatus("PROCESSING");
                claimed.add(event);
            }
        }
        return claimed;
    }

    public void succeeded(PaymentReconciliationEvent event) {
        PaymentReconciliationEvent update = new PaymentReconciliationEvent();
        update.setEventStatus("SUCCEEDED");
        update.setLastError("");
        update.setEditTime(DateUtils.now());
        if (mapper.update(update, processingEvent(event)) == 1) {
            meterRegistry.counter("stellaris_payment_reconciliation_total", "result", "succeeded").increment();
        }
    }

    public void failed(PaymentReconciliationEvent event, Throwable failure) {
        int attempts = Objects.requireNonNullElse(event.getRetryCount(), 0) + 1;
        PaymentReconciliationEvent update = new PaymentReconciliationEvent();
        update.setEventStatus("FAILED");
        update.setRetryCount(attempts);
        update.setNextRetryTime(new Date(System.currentTimeMillis()
                + Math.max(1000, retryBackoffMs) * Math.min(attempts, 30)));
        update.setLastError(abbreviate(failure == null ? "unknown" : failure.getMessage()));
        update.setEditTime(DateUtils.now());
        if (mapper.update(update, processingEvent(event)) == 1) {
            meterRegistry.counter("stellaris_payment_reconciliation_total", "result", "failed").increment();
        }
    }

    /** Expected business wait (unpaid or bill not materialized), excluded from failure alerts. */
    public void waiting(PaymentReconciliationEvent event, String reason) {
        PaymentReconciliationEvent update = new PaymentReconciliationEvent();
        update.setEventStatus("WAITING");
        update.setRetryCount(Objects.requireNonNullElse(event.getRetryCount(), 0));
        update.setNextRetryTime(new Date(System.currentTimeMillis() + Math.max(1000, retryBackoffMs)));
        update.setLastError(abbreviate(reason));
        update.setEditTime(DateUtils.now());
        if (mapper.update(update, processingEvent(event)) == 1) {
            meterRegistry.counter("stellaris_payment_reconciliation_total", "result", "waiting").increment();
        }
    }

    public void dead(PaymentReconciliationEvent event, PaymentReconciliationConflictException conflict) {
        PaymentReconciliationEvent update = new PaymentReconciliationEvent();
        update.setEventStatus("DEAD");
        update.setRetryCount(Objects.requireNonNullElse(event.getRetryCount(), 0) + 1);
        update.setLastError(abbreviate(conflict.getCode() + ": " + conflict.getMessage()));
        update.setEditTime(DateUtils.now());
        if (mapper.update(update, processingEvent(event)) == 1) {
            meterRegistry.counter("stellaris_payment_reconciliation_total", "result", "dead").increment();
            meterRegistry.counter("stellaris_payment_reconciliation_conflict_total",
                    "reason", conflict.getCode()).increment();
        }
    }

    private void recoverTimedOut() {
        Date stale = new Date(System.currentTimeMillis() - Math.max(1000, processingTimeoutMs));
        PaymentReconciliationEvent update = new PaymentReconciliationEvent();
        update.setEventStatus("FAILED");
        update.setNextRetryTime(DateUtils.now());
        update.setLastError("payment reconciliation lease expired");
        update.setEditTime(DateUtils.now());
        int recovered = mapper.update(update, Wrappers.lambdaUpdate(PaymentReconciliationEvent.class)
                .eq(PaymentReconciliationEvent::getEventStatus, "PROCESSING")
                .le(PaymentReconciliationEvent::getEditTime, stale));
        if (recovered > 0) {
            meterRegistry.counter("stellaris_payment_reconciliation_total", "result", "lease_recovered")
                    .increment(recovered);
        }
    }

    private void refreshOldestFailureAge() {
        try {
            PaymentReconciliationEvent oldest = mapper.selectOne(Wrappers.lambdaQuery(PaymentReconciliationEvent.class)
                    .eq(PaymentReconciliationEvent::getEventStatus, "FAILED")
                    .orderByAsc(PaymentReconciliationEvent::getCreateTime)
                    .orderByAsc(PaymentReconciliationEvent::getId)
                    .last("LIMIT 1"));
            if (oldest == null) {
                oldestFailedAgeSeconds.set(0);
                return;
            }
            Date since = oldest.getCreateTime();
            oldestFailedAgeSeconds.set(since == null ? 0
                    : Math.max(0, (System.currentTimeMillis() - since.getTime()) / 1000));
        } catch (RuntimeException observationFailure) {
            log.warn("支付对账失败年龄采集失败", observationFailure);
        }
    }

    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PaymentReconciliationEvent>
    processingEvent(PaymentReconciliationEvent event) {
        return Wrappers.lambdaUpdate(PaymentReconciliationEvent.class)
                .eq(PaymentReconciliationEvent::getId, event.getId())
                .eq(PaymentReconciliationEvent::getOrderNumber, event.getOrderNumber())
                .eq(PaymentReconciliationEvent::getEventStatus, "PROCESSING");
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
