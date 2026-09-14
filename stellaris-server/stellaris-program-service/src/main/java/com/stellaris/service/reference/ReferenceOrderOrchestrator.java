package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.SeatDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.service.ProgramOrderService;
import com.stellaris.vo.SeatVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * v5 唯一下单编排：每次候选尝试由一次 O(k) Lua 原子完成锁座、限购和 XADD。
 * Redis Lua 原子预占座位并写入 Stream；订单服务直接消费并在单交易库事务中落单。
 */
@Slf4j
@Service
public class ReferenceOrderOrchestrator {
    private static final int MAX_TICKETS_PER_REQUEST = 6;

    private final ReferenceSeatInventoryService inventoryService;
    private final ReferenceSeatReservationService reservationService;
    private final ProgramOrderService programOrderService;
    private final UidGenerator uidGenerator;
    private final ReferenceAutoSeatMetrics autoSeatMetrics;

    @Value("${reference-order.reservation-timeout-ms:900000}")
    private long reservationTimeoutMs = 900_000;
    @Value("${reference-order.candidate-limit:2000}")
    private int candidateLimit = 2_000;
    @Value("${reference-order.auto-seat-max-attempts:5}")
    private int autoSeatMaxAttempts = 5;
    @Value("${reference-order.expiry-cleanup-grace-ms:60000}")
    private long expiryCleanupGraceMs = 60_000;
    @Value("${reference-order.idempotency-receipt-ttl-ms:86400000}")
    private long idempotencyReceiptTtlMs = 86_400_000;
    @Value("${reference-order.max-stream-length:100000}")
    private long maxStreamLength = 100_000;

    public ReferenceOrderOrchestrator(ReferenceSeatInventoryService inventoryService,
                                      ReferenceSeatReservationService reservationService,
                                      ProgramOrderService programOrderService,
                                      UidGenerator uidGenerator,
                                      ReferenceAutoSeatMetrics autoSeatMetrics) {
        this.inventoryService = inventoryService;
        this.reservationService = reservationService;
        this.programOrderService = programOrderService;
        this.uidGenerator = uidGenerator;
        this.autoSeatMetrics = autoSeatMetrics;
    }

    public String create(ProgramOrderCreateDto request) {
        return create(request, null);
    }

    public Mono<ReferenceSeatInventoryService.CandidateSelection> prepareAutomaticSelectionAsync(
            ProgramOrderCreateDto request) {
        return inventoryService.findCandidateSelectionAsync(request.getProgramId(),
                request.getTicketCategoryId(), request.getTicketCount(),
                Math.max(request.getTicketCount(), candidateLimit), request.getRequestId());
    }

    public String create(ProgramOrderCreateDto request,
                         ReferenceSeatInventoryService.CandidateSelection preparedSelection) {
        validate(request);
        String intentId = digest(request.getProgramId() + ":" + request.getUserId() + ":" + request.getRequestId());
        String fingerprint = requestFingerprint(request);
        Long orderNumber = uidGenerator.getId();
        Long eventId = uidGenerator.getUid();
        long expireAtMillis = System.currentTimeMillis() + reservationTimeoutMs;
        if (request.getSeatDtoList() != null && !request.getSeatDtoList().isEmpty()) {
            List<SeatVo> seats = inventoryService.findByIds(request.getProgramId(),
                    request.getSeatDtoList().stream().map(SeatDto::getId).toList());
            SeatReservationResult result = reserve(request, intentId, fingerprint, orderNumber, eventId,
                    expireAtMillis, seats, inventoryService.currentVersion(request.getProgramId()));
            if (!result.success()) {
                throw reservationFailure(result);
            }
            return result.replayed() ? persistedOrderNumber(request.getProgramId(), intentId)
                    : String.valueOf(orderNumber);
        }
        return createAutomatic(request, intentId, fingerprint, orderNumber, eventId, expireAtMillis,
                preparedSelection);
    }

    private String createAutomatic(ProgramOrderCreateDto request, String intentId, String fingerprint,
                                   Long orderNumber, Long eventId, long expireAtMillis,
                                   ReferenceSeatInventoryService.CandidateSelection preparedSelection) {
        autoSeatMetrics.request();
        ReferenceSeatInventoryService.CandidateSelection selection = preparedSelection != null
                ? preparedSelection
                : inventoryService.findCandidateSelection(request.getProgramId(), request.getTicketCategoryId(),
                request.getTicketCount(), Math.max(request.getTicketCount(), candidateLimit), request.getRequestId());
        List<List<SeatVo>> groups = selection.groups();
        if (groups.isEmpty()) {
            autoSeatMetrics.finalFailure("NO_CANDIDATE_GROUP", 0);
            throw new StellarisFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        List<Integer> attemptOrder = candidateAttemptOrder(request.getRequestId(), groups.size(),
                request.getTicketCount(), Math.max(1, autoSeatMaxAttempts));
        SeatReservationResult lastResult = null;
        for (int attemptIndex = 0; attemptIndex < attemptOrder.size(); attemptIndex++) {
            List<SeatVo> seats = groups.get(attemptOrder.get(attemptIndex));
            long attemptStarted = System.nanoTime();
            SeatReservationResult result = reserve(request, intentId, fingerprint, orderNumber, eventId,
                    expireAtMillis, seats, selection.saleVersion());
            long elapsedMicros = (System.nanoTime() - attemptStarted) / 1_000;
            List<Long> candidateSeatIds = seats.stream().map(SeatVo::getId).toList();
            log.debug("autoSeatAttempt requestId={} userId={} programId={} ticketCategoryId={} "
                            + "retryIndex={} candidateSeatIds={} luaCode={} elapsedMicros={}",
                    request.getRequestId(), request.getUserId(), request.getProgramId(),
                    request.getTicketCategoryId(), attemptIndex, candidateSeatIds, result.code(), elapsedMicros);
            if (result.success()) {
                autoSeatMetrics.complete(attemptIndex);
                if (attemptIndex > 0) {
                    log.debug("autoSeatRecovered requestId={} userId={} programId={} ticketCategoryId={} "
                                    + "retryCount={} candidateSeatIds={} elapsedMicros={}",
                            request.getRequestId(), request.getUserId(), request.getProgramId(),
                            request.getTicketCategoryId(), attemptIndex, candidateSeatIds, elapsedMicros);
                }
                return result.replayed() ? persistedOrderNumber(request.getProgramId(), intentId)
                        : String.valueOf(orderNumber);
            }
            lastResult = result;
            if (!result.retryableSeatConflict()) {
                autoSeatMetrics.finalFailure(result.code(), attemptIndex);
                throw reservationFailure(result);
            }
            autoSeatMetrics.conflict(result.code());
            boolean hasNextCandidate = attemptIndex + 1 < attemptOrder.size();
            if (hasNextCandidate) {
                autoSeatMetrics.retry(result.code());
                log.debug("autoSeatConflictRetry requestId={} userId={} programId={} ticketCategoryId={} "
                                + "retryIndex={} candidateSeatIds={} luaCode={} elapsedMicros={}",
                        request.getRequestId(), request.getUserId(), request.getProgramId(),
                        request.getTicketCategoryId(), attemptIndex, candidateSeatIds, result.code(), elapsedMicros);
            }
        }
        String finalCode = lastResult == null ? "NO_ATTEMPT" : lastResult.code();
        autoSeatMetrics.finalFailure("CONFLICT_EXHAUSTED_" + finalCode,
                Math.max(0, attemptOrder.size() - 1));
        throw new StellarisFrameException(BaseCode.SEAT_SOLD);
    }

    private SeatReservationResult reserve(ProgramOrderCreateDto request, String intentId, String fingerprint,
                                          Long orderNumber, Long eventId, long expireAtMillis,
                                          List<SeatVo> seats, String saleVersion) {
        OrderCreateEvent message = programOrderService.buildReferenceOrderMessage(request, seats, orderNumber);
        message.setRequestId(request.getRequestId());
        message.setRequestFingerprint(fingerprint);
        message.setAccountLimit(request.getServerAccountLimit());
        message.setSaleVersion(saleVersion);
        message.setIntentId(intentId);
        message.setEventId(eventId);
        message.setSeatSnapshot(JSON.toJSONString(seats));
        message.setReservationExpireTime(new Date(expireAtMillis));
        SeatReservationRequest reservation = new SeatReservationRequest(intentId, request.getProgramId(),
                request.getUserId(), Objects.requireNonNullElse(request.getServerAccountLimit(), 0),
                fingerprint, JSON.toJSONString(message), expireAtMillis + expiryCleanupGraceMs,
                idempotencyReceiptTtlMs, maxStreamLength, toSeats(message));
        return reservationService.reserveManual(reservation);
    }

    private String persistedOrderNumber(Long programId, String intentId) {
        // 重复请求必须返回第一次生成的订单号，不能返回本次已废弃的新 ID。
        String persistedPayload = reservationService.eventPayload(programId, intentId);
        OrderCreateEvent persisted = persistedPayload == null ? null
                : JSON.parseObject(persistedPayload, OrderCreateEvent.class);
        if (persisted == null || persisted.getOrderNumber() == null) {
            throw new IllegalStateException("successful reservation has no recoverable stream payload: " + intentId);
        }
        return String.valueOf(persisted.getOrderNumber());
    }

    private StellarisFrameException reservationFailure(SeatReservationResult result) {
        if ("ACCOUNT_LIMIT_EXCEEDED".equals(result.code())) {
            return new StellarisFrameException(BaseCode.PER_ACCOUNT_PURCHASE_COUNT_OVER_LIMIT);
        }
        if ("IDEMPOTENCY_CONFLICT".equals(result.code())) {
            return new StellarisFrameException(BaseCode.PARAMETER_ERROR);
        }
        if ("ORDER_BACKLOG_LIMIT".equals(result.code())) {
            return new StellarisFrameException(BaseCode.ORDER_ACCEPTANCE_BUSY);
        }
        return new StellarisFrameException(BaseCode.SEAT_SOLD);
    }

    static List<Integer> candidateAttemptOrder(String requestId, int groupCount, int ticketCount,
                                               int maxAttempts) {
        if (requestId == null || requestId.isBlank() || groupCount <= 0 || maxAttempts <= 0) {
            return List.of();
        }
        int attempts = Math.min(groupCount, maxAttempts);
        int start = Math.floorMod(requestId.hashCode() * 31 + 0x9E3779B9, groupCount);
        int step = Math.max(1, ticketCount);
        while (greatestCommonDivisor(step, groupCount) != 1) {
            step++;
        }
        List<Integer> order = new ArrayList<>(attempts);
        for (int index = 0; index < attempts; index++) {
            order.add(Math.floorMod(start + index * step, groupCount));
        }
        return List.copyOf(order);
    }

    private static int greatestCommonDivisor(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return Math.max(1, a);
    }

    private String requestFingerprint(ProgramOrderCreateDto request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("programId", request.getProgramId());
        canonical.put("userId", request.getUserId());
        canonical.put("ticketUserIds", request.getTicketUserIdList());
        canonical.put("ticketCategoryId", request.getTicketCategoryId());
        canonical.put("ticketCount", request.getTicketCount());
        canonical.put("seatIds", request.getSeatDtoList() == null ? List.of()
                : request.getSeatDtoList().stream().map(SeatDto::getId).toList());
        return digest(JSON.toJSONString(canonical));
    }

    private List<SeatReservationRequest.Seat> toSeats(OrderCreateEvent message) {
        return message.getOrderTicketUserCreateDtoList().stream()
                .map(ticket -> new SeatReservationRequest.Seat(ticket.getSeatId(), ticket.getTicketCategoryId(),
                        ticket.getOrderPrice().movePointRight(2).longValueExact(), ticket.getTicketUserId()))
                .toList();
    }

    private void validate(ProgramOrderCreateDto request) {
        if (request.getProgramId() == null || request.getProgramId() <= 0
                || request.getUserId() == null || request.getUserId() <= 0
                || request.getRequestId() == null || request.getRequestId().isBlank()
                || request.getTicketUserIdList() == null || request.getTicketUserIdList().isEmpty()
                || request.getTicketUserIdList().size() > MAX_TICKETS_PER_REQUEST
                || request.getTicketUserIdList().stream().anyMatch(Objects::isNull)
                || request.getTicketUserIdList().stream().distinct().count() != request.getTicketUserIdList().size()) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR);
        }
        int count = request.getTicketUserIdList().size();
        if (request.getSeatDtoList() != null && !request.getSeatDtoList().isEmpty()) {
            if (request.getSeatDtoList().size() != count
                    || request.getSeatDtoList().stream().map(SeatDto::getId).anyMatch(Objects::isNull)
                    || request.getSeatDtoList().stream().map(SeatDto::getId).distinct().count() != count) {
                throw new StellarisFrameException(BaseCode.TICKET_USER_COUNT_UNEQUAL_SEAT_COUNT);
            }
        } else if (request.getTicketCategoryId() == null || request.getTicketCount() == null
                || request.getTicketCount() <= 0 || request.getTicketCount() != count) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR);
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
