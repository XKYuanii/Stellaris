package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.client.OrderClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.ProgramService;
import com.stellaris.vo.ReferenceOrderStateVo;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v5 Redis Stream 版只读对账：核验 Stream/死信、Redis 预订归属以及订单服务事实。
 * 不再查询或推进 MySQL Intent/Outbox 状态。
 */
@Service
public class ReferenceReconciliationExecutor {
    private final ProgramService programService;
    private final OrderClient orderClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    @Value("${reference-reconciliation.batch-size:100}")
    private int batchSize;
    @Value("${reference-reconciliation.max-programs:100}")
    private int maxPrograms;
    @Value("${reference-reconciliation.order-grace-ms:60000}")
    private long orderGraceMs;

    public ReferenceReconciliationExecutor(ProgramService programService, OrderClient orderClient,
                                           RedisCache redisCache, MeterRegistry meterRegistry) {
        this.programService = programService;
        this.orderClient = orderClient;
        this.redisTemplate = redisCache.getInstance();
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("stellaris_reference_reconciliation_last_success_epoch_seconds", lastSuccessEpochSeconds);
    }

    public ReferenceReconciliationReport execute() {
        ReferenceReconciliationReport report = new ReferenceReconciliationReport();
        report.setStartedAt(new Date());
        Map<Long, ReservationFact> reservations = new HashMap<>();
        List<Long> programIds = programService.getAllProgramIdList();
        programIds.stream().limit(Math.max(1, maxPrograms)).forEach(programId -> inspectProgram(programId, reservations, report));
        inspectStreams(report);
        inspectOrders(reservations, report);
        report.setCompletedAt(new Date());
        lastSuccessEpochSeconds.set(System.currentTimeMillis() / 1000);
        meterRegistry.counter("stellaris_reference_reconciliation_runs_total", "result", "success").increment();
        meterRegistry.counter("stellaris_reference_reconciliation_findings_total")
                .increment(report.getFindings().size());
        return report;
    }

    private void inspectProgram(long programId, Map<Long, ReservationFact> facts,
                                ReferenceReconciliationReport report) {
        report.setInspectedPrograms(report.getInspectedPrograms() + 1);
        ScanOptions options = ScanOptions.scanOptions().count(Math.max(1, batchSize)).build();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash()
                .scan(SeatReservationKeys.reservation(programId), options)) {
            int inspected = 0;
            while (cursor.hasNext() && inspected++ < Math.max(1, batchSize)) {
                Map.Entry<Object, Object> entry = cursor.next();
                String intentId = String.valueOf(entry.getKey());
                JSONObject reservation = JSON.parseObject(String.valueOf(entry.getValue()));
                OrderCreateMq message = JSON.parseObject(reservation.getString("eventPayload"), OrderCreateMq.class);
                report.setInspectedIntents(report.getInspectedIntents() + 1);
                if (message == null || message.getOrderNumber() == null) {
                    report.finding("RESERVATION", "PAYLOAD_MISSING", programId, intentId, null,
                            "Redis reservation has no recoverable order event");
                    continue;
                }
                facts.put(message.getOrderNumber(), new ReservationFact(intentId, message));
                JSONArray seats = reservation.getJSONArray("seats");
                if (seats == null) {
                    report.finding("INVENTORY", "SEATS_MISSING", programId, intentId,
                            message.getOrderNumber(), "reservation has no seats");
                    continue;
                }
                for (int i = 0; i < seats.size(); i++) {
                    JSONObject seat = seats.getJSONObject(i);
                    String seatId = seat.getString("seatId");
                    String owner = Objects.toString(redisTemplate.opsForHash()
                            .get(SeatReservationKeys.owner(programId), seatId), null);
                    if (!intentId.equals(owner)) {
                        report.finding("INVENTORY", "OWNER_MISMATCH", programId, intentId,
                                message.getOrderNumber(), "seat " + seatId + " owner=" + owner);
                    }
                    String available = SeatReservationKeys.available(programId,
                            seat.getLongValue("ticketCategoryId"));
                    if (redisTemplate.opsForZSet().score(available, seatId) != null) {
                        report.finding("INVENTORY", "HELD_SEAT_STILL_AVAILABLE", programId, intentId,
                                message.getOrderNumber(), "seat " + seatId + " remains in available ZSET");
                    }
                }
            }
        }
    }

    private void inspectStreams(ReferenceReconciliationReport report) {
        for (int shard = 0; shard < SeatReservationKeys.SALE_SHARD_COUNT; shard++) {
            Long backlog = redisTemplate.opsForStream().size(SeatReservationKeys.eventStream(shard));
            Long dead = redisTemplate.opsForStream().size(SeatReservationKeys.eventDeadStream(shard));
            report.setInspectedEvents(report.getInspectedEvents()
                    + Objects.requireNonNullElse(backlog, 0L).intValue()
                    + Objects.requireNonNullElse(dead, 0L).intValue());
            if (dead != null && dead > 0) {
                report.finding("EVENT", "REDIS_STREAM_DEAD", null, null, null,
                        "shard=" + shard + ", dead=" + dead);
            }
        }
    }

    private void inspectOrders(Map<Long, ReservationFact> reservations, ReferenceReconciliationReport report) {
        if (reservations.isEmpty()) {
            return;
        }
        List<Long> orderNumbers = new ArrayList<>(reservations.keySet());
        ReferenceOrderStateQueryDto query = new ReferenceOrderStateQueryDto();
        query.setOrderNumbers(orderNumbers);
        ApiResponse<List<ReferenceOrderStateVo>> response = orderClient.referenceStateBatch(query);
        if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
            report.finding("ORDER", "QUERY_FAILED", null, null, null, "order batch query failed");
            return;
        }
        Map<Long, ReferenceOrderStateVo> orders = new HashMap<>();
        Objects.requireNonNullElse(response.getData(), List.<ReferenceOrderStateVo>of())
                .forEach(order -> orders.put(order.getOrderNumber(), order));
        report.setInspectedOrders(orders.size());
        long now = System.currentTimeMillis();
        reservations.forEach((orderNumber, fact) -> {
            if (orders.containsKey(orderNumber)) {
                return;
            }
            Date expires = fact.message().getReservationExpireTime();
            if (expires != null && now > expires.getTime() + orderGraceMs) {
                report.finding("ORDER", "ORDER_MISSING_AFTER_GRACE", fact.message().getProgramId(),
                        fact.intentId(), orderNumber, "reservation exists but order was not created");
            }
        });
    }

    private record ReservationFact(String intentId, OrderCreateMq message) {
    }
}
