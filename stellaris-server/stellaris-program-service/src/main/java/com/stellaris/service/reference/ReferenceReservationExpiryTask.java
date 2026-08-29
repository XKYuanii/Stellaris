package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.client.OrderClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.ProgramService;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.vo.ReferenceOrderStateVo;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 超过预订截止时间与清理宽限期后，只释放订单服务中仍不存在的 Redis 预订。 */
@Slf4j
@Component
public class ReferenceReservationExpiryTask {
    private final ProgramService programService;
    private final OrderClient orderClient;
    private final ReferenceSeatReservationService reservationService;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${reference-reservation-expiry.enabled:true}")
    private boolean enabled;
    @Value("${reference-reservation-expiry.batch-size:100}")
    private int batchSize;
    @Value("${reference-reservation-expiry.max-programs:100}")
    private int maxPrograms;
    @Value("${reference-order-stream.group:stellaris-order-relay}")
    private String relayGroup;

    public ReferenceReservationExpiryTask(ProgramService programService, OrderClient orderClient,
                                          ReferenceSeatReservationService reservationService,
                                          RedisCache redisCache, MeterRegistry meterRegistry) {
        this.programService = programService;
        this.orderClient = orderClient;
        this.reservationService = reservationService;
        this.redisTemplate = redisCache.getInstance();
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${reference-reservation-expiry.fixed-delay-ms:30000}")
    @ServiceLock(name = "reference-reservation-expiry", keys = {})
    public void run() {
        if (!enabled) {
            return;
        }
        programService.getAllProgramIdList().stream().limit(Math.max(1, maxPrograms))
                .forEach(this::releaseExpiredForProgram);
    }

    private void releaseExpiredForProgram(long programId) {
        Set<String> intentIds = redisTemplate.opsForZSet().rangeByScore(
                SeatReservationKeys.expiration(programId), 0, System.currentTimeMillis(),
                0, Math.max(1, batchSize));
        if (intentIds == null) {
            return;
        }
        for (String intentId : intentIds) {
            try {
                releaseIfOrderMissing(programId, intentId);
            } catch (Exception ex) {
                meterRegistry.counter("stellaris_reference_reservation_expiry_total", "result", "failed").increment();
                log.error("到期 Redis 预订收敛失败 programId:{} intentId:{}", programId, intentId, ex);
            }
        }
    }

    private void releaseIfOrderMissing(long programId, String intentId) {
        Object raw = redisTemplate.opsForHash().get(SeatReservationKeys.reservation(programId), intentId);
        if (raw == null) {
            redisTemplate.opsForZSet().remove(SeatReservationKeys.expiration(programId), intentId);
            return;
        }
        JSONObject reservation = JSON.parseObject(String.valueOf(raw));
        OrderCreateMq message = JSON.parseObject(reservation.getString("eventPayload"), OrderCreateMq.class);
        if (message == null || message.getOrderNumber() == null) {
            throw new IllegalStateException("expired reservation has no order payload");
        }
        String streamId = reservation.getString("streamId");
        if (!sourceEventStillExists(programId, streamId)) {
            // 源记录只有在 Kafka 确认接收或移入 dead stream 后才会删除；此时下游可能仍在处理，禁止误释放。
            meterRegistry.counter("stellaris_reference_reservation_expiry_total", "result", "downstream_owned")
                    .increment();
            return;
        }
        ReferenceOrderStateQueryDto query = new ReferenceOrderStateQueryDto();
        query.setOrderNumbers(List.of(message.getOrderNumber()));
        ApiResponse<List<ReferenceOrderStateVo>> response = orderClient.referenceStateBatch(query);
        if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
            return;
        }
        if (response.getData() != null && !response.getData().isEmpty()) {
            return;
        }
        if (programService.hasLockedReservation(programId, intentId)) {
            meterRegistry.counter("stellaris_reference_reservation_expiry_total", "result", "database_owned")
                    .increment();
            return;
        }
        if (message.getOrderTicketUserCreateDtoList() == null
                || message.getOrderTicketUserCreateDtoList().isEmpty()) {
            throw new IllegalStateException("expired reservation has no ticket users");
        }
        List<Long> categories = message.getOrderTicketUserCreateDtoList().stream()
                .map(ticket -> ticket.getTicketCategoryId()).distinct().toList();
        reservationService.release(programId, intentId, categories);
        acknowledgeDeletedPending(programId, streamId);
        meterRegistry.counter("stellaris_reference_reservation_expiry_total", "result", "released").increment();
        log.warn("已释放到期且无订单事实的 Redis 预订 programId:{} intentId:{} orderNumber:{}",
                programId, intentId, message.getOrderNumber());
    }

    private boolean sourceEventStillExists(long programId, String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return false;
        }
        List<?> records = redisTemplate.opsForStream().range(
                SeatReservationKeys.eventStream(SeatReservationKeys.shard(programId)),
                org.springframework.data.domain.Range.closed(streamId, streamId));
        return records != null && !records.isEmpty();
    }

    private void acknowledgeDeletedPending(long programId, String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForStream().acknowledge(
                    SeatReservationKeys.eventStream(SeatReservationKeys.shard(programId)),
                    relayGroup, RecordId.of(streamId));
        } catch (RuntimeException ignored) {
            // 消费组尚未创建或消息从未进入 PEL 时无需处理；源记录已在释放 Lua 中删除。
        }
    }
}
