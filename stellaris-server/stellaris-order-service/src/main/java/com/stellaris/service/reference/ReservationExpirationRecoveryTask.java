package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderRequest;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderRequestMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.OrderService;
import com.stellaris.service.trade.OrderReservationRejectedException;
import com.stellaris.service.trade.TradeOrderCreateService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * Shard-wide reservation expiry recovery. MySQL decides the final action; Redis time alone never
 * releases a paid order. This also materializes a stable rejection for orphan reservations.
 */
@Slf4j
@Component
public class ReservationExpirationRecoveryTask {
    private final StringRedisTemplate redisTemplate;
    private final OrderRequestMapper requestMapper;
    private final OrderMapper orderMapper;
    private final TradeOrderCreateService createService;
    private final OrderService orderService;
    private final OrderReservationReleaseService transitionService;
    private final MeterRegistry meterRegistry;

    @Value("${reservation-expiration.batch-size:100}")
    private int batchSize;
    @Value("${reservation-expiration.grace-ms:60000}")
    private long graceMs;
    @Value("${reservation-expiration.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    public ReservationExpirationRecoveryTask(RedisCache redisCache, OrderRequestMapper requestMapper,
                                             OrderMapper orderMapper, TradeOrderCreateService createService,
                                             OrderService orderService,
                                             OrderReservationReleaseService transitionService,
                                             MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.requestMapper = requestMapper;
        this.orderMapper = orderMapper;
        this.createService = createService;
        this.orderService = orderService;
        this.transitionService = transitionService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${reservation-expiration.fixed-delay-ms:10000}")
    public void recover() {
        double dueBefore = System.currentTimeMillis() - Math.max(0, graceMs);
        long limit = Math.max(1, Math.min(batchSize, 500));
        for (int shard = 0; shard < OrderReservationStreamKeys.SALE_SHARD_COUNT; shard++) {
            String index = OrderReservationStreamKeys.expirationIndex(shard);
            Set<String> due = redisTemplate.opsForZSet().rangeByScore(index, 0, dueBefore, 0, limit);
            if (due == null) continue;
            for (String member : due) {
                try {
                    recoverOne(member);
                    meterRegistry.counter("stellaris_reservation_expiration_recovery_total",
                            "result", "success").increment();
                } catch (RuntimeException failure) {
                    defer(index, member, failure);
                }
            }
        }
    }

    /**
     * Keep failed evidence durable while moving it out of the current page. The read threshold
     * subtracts graceMs, so the stored score compensates for that grace to retry after backoffMs.
     */
    private void defer(String index, String member, RuntimeException failure) {
        double retryScore = System.currentTimeMillis() - Math.max(0, graceMs)
                + Math.max(1_000, retryBackoffMs);
        try {
            Boolean updated = redisTemplate.opsForZSet().add(index, member, retryScore);
            meterRegistry.counter("stellaris_reservation_expiration_recovery_total",
                    "result", Boolean.FALSE.equals(updated) ? "deferred_existing" : "deferred").increment();
            log.warn("预约过期恢复失败，已推迟重试 member:{}", member, failure);
        } catch (RuntimeException rescheduleFailure) {
            meterRegistry.counter("stellaris_reservation_expiration_recovery_total",
                    "result", "defer_failed").increment();
            log.error("预约过期恢复失败且无法更新重试时间 member:{}", member, rescheduleFailure);
        }
    }

    void recoverOne(String member) {
        OrderReservationStreamKeys.ReservationExpiration expiration =
                OrderReservationStreamKeys.parseExpirationMember(member);
        long programId = expiration.programId();
        String intentId = expiration.intentId();
        OrderRequest request = requestMapper.selectOne(Wrappers.lambdaQuery(OrderRequest.class)
                .eq(OrderRequest::getReservationId, intentId));
        if (request == null) {
            materializeOrphanRejection(programId, intentId);
            transitionService.release(programId, intentId);
            return;
        }
        if ("REJECTED".equals(request.getResultStatus())) {
            transitionService.release(programId, intentId);
            return;
        }
        if (!"CREATED".equals(request.getResultStatus())) {
            throw new IllegalStateException("reservation request is still processing: " + intentId);
        }
        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderNumber, request.getOrderNumber()));
        if (order == null) throw new IllegalStateException("created request has no order: " + intentId);
        if (!Objects.equals(order.getProgramId(), programId) || !Objects.equals(order.getIntentId(), intentId)) {
            throw new IllegalStateException("reservation and order identity mismatch: " + intentId);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            if (order.getExpireTime() == null || order.getExpireTime().after(new java.util.Date())) {
                throw new IllegalStateException("MySQL order deadline has not expired: " + order.getOrderNumber());
            }
            orderService.updateOrderRelatedData(order.getOrderNumber(), OrderStatus.CANCEL);
            transitionService.release(programId, intentId);
            return;
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) {
            transitionService.confirmSale(programId, intentId);
            return;
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())
                || Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            transitionService.release(programId, intentId);
            return;
        }
        throw new IllegalStateException("unsupported order status: " + order.getOrderStatus());
    }

    private void materializeOrphanRejection(long programId, String intentId) {
        Object raw = redisTemplate.opsForHash().get(reservationKey(programId), intentId);
        if (raw == null) return;
        try {
            JSONObject reservation = JSON.parseObject(String.valueOf(raw));
            OrderCreateEvent message = JSON.parseObject(reservation.getString("eventPayload"), OrderCreateEvent.class);
            if (message != null) createService.recordRejected(message, "RESERVATION_EXPIRED");
        } catch (OrderReservationRejectedException conflict) {
            log.warn("过期预约拒绝结果与现有幂等记录冲突 intentId:{}", intentId);
        } catch (RuntimeException malformed) {
            // Release still remains safe because programId/intentId came from the server-owned index.
            log.warn("过期预约 payload 无法物化拒绝结果 intentId:{}", intentId, malformed);
        }
    }

    private String reservationKey(long programId) {
        int shard = OrderReservationStreamKeys.shard(programId);
        return "stellaris:{sale:" + shard + "}:program:" + programId + ":seat:reservation";
    }
}
