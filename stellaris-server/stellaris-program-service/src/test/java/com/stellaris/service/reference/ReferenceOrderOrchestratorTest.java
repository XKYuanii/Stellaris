package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.service.ProgramOrderService;
import com.stellaris.vo.SeatVo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ReferenceOrderOrchestratorTest {

    @Test
    void retryReturnsOriginalOrderBeforeRunningAutomaticSeatSelection() throws Exception {
        ReferenceSeatInventoryService inventory = mock(ReferenceSeatInventoryService.class);
        ReferenceSeatReservationService reservations = mock(ReferenceSeatReservationService.class);
        ProgramOrderService orderService = mock(ProgramOrderService.class);
        UidGenerator uidGenerator = mock(UidGenerator.class);
        ReferenceAutoSeatMetrics metrics = mock(ReferenceAutoSeatMetrics.class);
        ReferenceOrderOrchestrator orchestrator = new ReferenceOrderOrchestrator(
                inventory, reservations, orderService, uidGenerator, metrics);

        ProgramOrderCreateDto request = new ProgramOrderCreateDto();
        request.setRequestId("retry-1");
        request.setProgramId(11L);
        request.setUserId(22L);
        request.setTicketUserIdList(List.of(33L));
        request.setTicketCategoryId(44L);
        request.setTicketCount(1);

        String reservationId = sha256("11:22:retry-1");
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("programId", 11L);
        canonical.put("userId", 22L);
        canonical.put("ticketUserIds", List.of(33L));
        canonical.put("ticketCategoryId", 44L);
        canonical.put("ticketCount", 1);
        canonical.put("seatIds", List.of());
        String fingerprint = sha256(JSON.toJSONString(canonical));

        OrderCreateMq original = new OrderCreateMq();
        original.setOrderNumber(20260826001L);
        when(reservations.eventPayload(11L, reservationId)).thenReturn(JSON.toJSONString(original));
        when(reservations.requestFingerprint(11L, reservationId)).thenReturn(fingerprint);

        assertThat(orchestrator.create(request)).isEqualTo("20260826001");
        verifyNoInteractions(inventory, orderService, uidGenerator);
    }

    @Test
    void retryDoesNotReturnReceiptAfterReservationWasReleased() throws Exception {
        ReferenceSeatInventoryService inventory = mock(ReferenceSeatInventoryService.class);
        ReferenceSeatReservationService reservations = mock(ReferenceSeatReservationService.class);
        ProgramOrderService orderService = mock(ProgramOrderService.class);
        UidGenerator uidGenerator = mock(UidGenerator.class);
        ReferenceAutoSeatMetrics metrics = mock(ReferenceAutoSeatMetrics.class);
        ReferenceOrderOrchestrator orchestrator = new ReferenceOrderOrchestrator(
                inventory, reservations, orderService, uidGenerator, metrics);

        ProgramOrderCreateDto request = new ProgramOrderCreateDto();
        request.setRequestId("released-1");
        request.setProgramId(11L);
        request.setUserId(22L);
        request.setTicketUserIdList(List.of(33L));
        request.setTicketCategoryId(44L);
        request.setTicketCount(1);
        String reservationId = sha256("11:22:released-1");
        when(reservations.eventPayload(11L, reservationId)).thenReturn("{\"orderNumber\":1}");
        when(reservations.requestFingerprint(11L, reservationId)).thenReturn(fingerprint(request));
        when(reservations.finalState(11L, reservationId)).thenReturn("RELEASED");

        assertThatThrownBy(() -> orchestrator.create(request)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(inventory, orderService, uidGenerator);
    }

    @Test
    void automaticSeatConflictMovesToAnotherCandidateWithoutChangingOrderIdentity() throws Exception {
        ReferenceSeatInventoryService inventory = mock(ReferenceSeatInventoryService.class);
        ReferenceSeatReservationService reservations = mock(ReferenceSeatReservationService.class);
        ProgramOrderService orderService = mock(ProgramOrderService.class);
        UidGenerator uidGenerator = mock(UidGenerator.class);
        ReferenceAutoSeatMetrics metrics = mock(ReferenceAutoSeatMetrics.class);
        ReferenceOrderOrchestrator orchestrator = new ReferenceOrderOrchestrator(
                inventory, reservations, orderService, uidGenerator, metrics);
        ProgramOrderCreateDto request = automaticRequest("spread-1");
        String intentId = sha256("11:22:spread-1");
        List<List<SeatVo>> groups = List.of(List.of(seat(101L, 1)), List.of(seat(102L, 2)),
                List.of(seat(103L, 3)), List.of(seat(104L, 4)));
        when(inventory.findCandidateGroups(eq(11L), eq(44L), eq(1), any(Integer.class), eq("spread-1")))
                .thenReturn(groups);
        when(uidGenerator.getOrderNumber(22L)).thenReturn(20260827001L);
        when(uidGenerator.getUid()).thenReturn(20260827002L);
        when(orderService.buildReferenceOrderMessage(eq(request), anyList(), eq(20260827001L)))
                .thenAnswer(invocation -> message(20260827001L, invocation.getArgument(1)));
        when(reservations.reserveManual(any(SeatReservationRequest.class))).thenReturn(
                new SeatReservationResult(false, false, "SEAT_UNAVAILABLE", List.of()),
                new SeatReservationResult(true, false, "OK", List.of()));
        OrderCreateMq persisted = new OrderCreateMq();
        persisted.setOrderNumber(20260827001L);
        when(reservations.eventPayload(11L, intentId)).thenReturn(null, JSON.toJSONString(persisted));

        assertThat(orchestrator.create(request)).isEqualTo("20260827001");

        ArgumentCaptor<SeatReservationRequest> captor = ArgumentCaptor.forClass(SeatReservationRequest.class);
        verify(reservations, times(2)).reserveManual(captor.capture());
        assertThat(captor.getAllValues()).extracting(SeatReservationRequest::intentId)
                .containsOnly(intentId);
        assertThat(captor.getAllValues()).extracting(value -> value.seats().get(0).seatId())
                .doesNotHaveDuplicates();
        assertThat(captor.getAllValues()).extracting(value ->
                        JSON.parseObject(value.eventPayload(), OrderCreateMq.class).getOrderNumber())
                .containsOnly(20260827001L);
        verify(metrics).request();
        verify(metrics).conflict("SEAT_UNAVAILABLE");
        verify(metrics).retry("SEAT_UNAVAILABLE");
        verify(metrics).complete(1);
    }

    @Test
    void candidateAttemptOrderIsStableAndDoesNotRepeatGroups() {
        List<Integer> first = ReferenceOrderOrchestrator.candidateAttemptOrder("request-123", 20, 2, 5);
        List<Integer> second = ReferenceOrderOrchestrator.candidateAttemptOrder("request-123", 20, 2, 5);

        assertThat(first).isEqualTo(second).hasSize(5).doesNotHaveDuplicates();
    }

    private static ProgramOrderCreateDto automaticRequest(String requestId) {
        ProgramOrderCreateDto request = new ProgramOrderCreateDto();
        request.setRequestId(requestId);
        request.setProgramId(11L);
        request.setUserId(22L);
        request.setTicketUserIdList(List.of(33L));
        request.setTicketCategoryId(44L);
        request.setTicketCount(1);
        request.setServerAccountLimit(6);
        return request;
    }

    private static SeatVo seat(long id, int column) {
        SeatVo seat = new SeatVo();
        seat.setId(id);
        seat.setTicketCategoryId(44L);
        seat.setRowCode(1);
        seat.setColCode(column);
        seat.setPrice(BigDecimal.valueOf(199));
        return seat;
    }

    private static OrderCreateMq message(long orderNumber, List<SeatVo> seats) {
        OrderCreateMq message = new OrderCreateMq();
        message.setOrderNumber(orderNumber);
        List<OrderTicketUserCreateDto> tickets = seats.stream().map(seat -> {
            OrderTicketUserCreateDto ticket = new OrderTicketUserCreateDto();
            ticket.setSeatId(seat.getId());
            ticket.setTicketCategoryId(seat.getTicketCategoryId());
            ticket.setTicketUserId(33L);
            ticket.setOrderPrice(seat.getPrice());
            return ticket;
        }).toList();
        message.setOrderTicketUserCreateDtoList(tickets);
        return message;
    }

    private static String fingerprint(ProgramOrderCreateDto request) throws Exception {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("programId", request.getProgramId());
        canonical.put("userId", request.getUserId());
        canonical.put("ticketUserIds", request.getTicketUserIdList());
        canonical.put("ticketCategoryId", request.getTicketCategoryId());
        canonical.put("ticketCount", request.getTicketCount());
        canonical.put("seatIds", List.of());
        return sha256(JSON.toJSONString(canonical));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
