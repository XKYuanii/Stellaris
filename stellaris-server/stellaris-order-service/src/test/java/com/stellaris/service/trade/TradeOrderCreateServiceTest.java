package com.stellaris.service.trade;

import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.entity.OrderRequest;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderProgram;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.mapper.AccountProgramPurchaseMapper;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.mapper.OrderRequestMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.mapper.TradeSeatInventoryMapper;
import com.stellaris.service.reference.ReservationTransitionEventService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeOrderCreateServiceTest {

    @Test
    void createsOrderOnlyAfterLimitAndEverySeatCasSucceeds() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = message();
        when(fixture.requestMapper.acquire(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(1);
        when(fixture.purchaseMapper.incrementWithinLimit(10L, 20L, 1, 6)).thenReturn(1);
        when(fixture.inventoryMapper.lockSeats(eq(10L), eq("sale-v1"), eq("reservation-1"),
                eq(1001L), anyList())).thenReturn(1);
        when(fixture.requestMapper.markCreated("reservation-1")).thenReturn(1);
        when(fixture.uidGenerator.getUid()).thenReturn(501L, 502L, 503L);

        assertThat(fixture.service.create(message)).isEqualTo("1001");

        verify(fixture.purchaseMapper).initialize(10L, 20L);
        verify(fixture.inventoryMapper).lockSeats(eq(10L), eq("sale-v1"), eq("reservation-1"), eq(1001L),
                argThat(seats -> seats.size() == 1
                        && seats.get(0).getSeatId() == 31L
                        && seats.get(0).getTicketCategoryId() == 41L
                        && seats.get(0).getPriceInCents() == 12800L));
        verify(fixture.orderMapper).insert(any(Order.class));
        verify(fixture.ticketMapper).insert(any(OrderTicketUser.class));
        verify(fixture.orderProgramMapper).insert(any(OrderProgram.class));
        verify(fixture.requestMapper).markCreated("reservation-1");
    }

    @Test
    void rollsBackWholeOrderWhenBatchCasLocksFewerSeatsThanRequested() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = twoSeatMessage();
        when(fixture.requestMapper.acquire(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(1);
        when(fixture.purchaseMapper.incrementWithinLimit(10L, 20L, 2, 6)).thenReturn(1);
        // 两座只锁住一座：另一座已被抢，或票档、价格、销售版本任一不匹配。
        // 批量 CAS 不区分是哪一座失败，影响行数不等于座位数即整单回滚。
        when(fixture.inventoryMapper.lockSeats(eq(10L), eq("sale-v1"), eq("reservation-1"),
                eq(1001L), anyList())).thenReturn(1);

        assertThatThrownBy(() -> fixture.service.create(message))
                .isInstanceOf(OrderReservationRejectedException.class)
                .hasMessage("SEAT_NOT_AVAILABLE");

        verify(fixture.orderMapper, never()).insert(any(Order.class));
        verify(fixture.ticketMapper, never()).insert(any(OrderTicketUser.class));
        verify(fixture.requestMapper, never()).markCreated(anyString());
    }

    @Test
    void sortsSeatsBySeatIdBeforeBatchLockingSoTheScanOrderStaysStable() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = twoSeatMessage();
        when(fixture.requestMapper.acquire(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(1);
        when(fixture.purchaseMapper.incrementWithinLimit(10L, 20L, 2, 6)).thenReturn(1);
        when(fixture.inventoryMapper.lockSeats(eq(10L), eq("sale-v1"), eq("reservation-1"),
                eq(1001L), anyList())).thenReturn(2);
        when(fixture.requestMapper.markCreated("reservation-1")).thenReturn(1);
        when(fixture.uidGenerator.getUid()).thenReturn(501L, 502L, 503L, 504L);

        assertThat(fixture.service.create(message)).isEqualTo("1001");

        // 入参故意是 32 在前、31 在后；IN 列表必须按 seat_id 升序，
        // 与主键扫描顺序一致，多单并发时才不会互相交叉持锁。
        verify(fixture.inventoryMapper).lockSeats(eq(10L), eq("sale-v1"), eq("reservation-1"), eq(1001L),
                argThat(seats -> seats.size() == 2
                        && seats.get(0).getSeatId() == 31L
                        && seats.get(1).getSeatId() == 32L));
    }

    @Test
    void zeroAccountLimitMeansUnlimitedAndStillRecordsThePurchaseCount() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = message();
        message.setAccountLimit(0);
        when(fixture.requestMapper.acquire(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(1);
        when(fixture.purchaseMapper.incrementWithinLimit(10L, 20L, 1, 0)).thenReturn(1);
        when(fixture.inventoryMapper.lockSeats(eq(10L), eq("sale-v1"), eq("reservation-1"),
                eq(1001L), anyList())).thenReturn(1);
        when(fixture.requestMapper.markCreated("reservation-1")).thenReturn(1);
        when(fixture.uidGenerator.getUid()).thenReturn(501L, 502L, 503L);

        assertThat(fixture.service.create(message)).isEqualTo("1001");

        verify(fixture.purchaseMapper).incrementWithinLimit(10L, 20L, 1, 0);
        verify(fixture.orderMapper).insert(any(Order.class));
    }

    @Test
    void rejectsHeaderAndDetailPriceMismatchBeforeTouchingInventory() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = message();
        message.setOrderPrice(new BigDecimal("127.99"));
        when(fixture.requestMapper.acquire(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(1);

        assertThatThrownBy(() -> fixture.service.create(message))
                .isInstanceOf(OrderReservationRejectedException.class)
                .hasMessage("ORDER_PRICE_MISMATCH");

        verify(fixture.purchaseMapper, never()).initialize(anyLong(), anyLong());
        verify(fixture.inventoryMapper, never()).lockSeats(anyLong(), anyString(), anyString(),
                anyLong(), anyList());
        verify(fixture.orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void rejectsOverlongSeatMetadataBeforeItCanPoisonTheStreamPel() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = message();
        message.getOrderTicketUserCreateDtoList().get(0).setSeatInfo("x".repeat(101));

        assertThatThrownBy(() -> fixture.service.create(message))
                .isInstanceOf(OrderReservationRejectedException.class)
                .hasMessage("INVALID_RESERVATION");

        verify(fixture.requestMapper, never()).acquire(anyString(), anyLong(), anyLong(), anyString(),
                anyLong(), anyString());
    }

    @Test
    void duplicateCreatedRequestReturnsOriginalResultWithoutRepeatingWrites() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = message();
        when(fixture.requestMapper.acquire(anyString(), anyLong(), anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(0);
        OrderRequest existing = new OrderRequest();
        existing.setReservationId("reservation-1");
        existing.setProgramId(10L);
        existing.setUserId(20L);
        existing.setRequestId("request-1");
        existing.setOrderNumber(1001L);
        existing.setRequestFingerprint("fingerprint-1");
        existing.setResultStatus("CREATED");
        when(fixture.requestMapper.selectOne(any())).thenReturn(existing);

        assertThat(fixture.service.create(message)).isEqualTo("1001");

        verify(fixture.purchaseMapper, never()).initialize(anyLong(), anyLong());
        verify(fixture.inventoryMapper, never()).lockSeats(anyLong(), anyString(), anyString(),
                anyLong(), anyList());
    }

    @Test
    void recordsMalformedReservationWithoutTryingAnUnknownRedisRelease() {
        Fixture fixture = new Fixture();
        OrderCreateEvent message = message();
        message.setOrderTicketUserCreateDtoList(null);
        when(fixture.requestMapper.recordRejected(anyString(), anyLong(), anyLong(), anyString(),
                anyLong(), anyString(), anyString())).thenReturn(1);
        OrderRequest rejected = new OrderRequest();
        rejected.setReservationId("reservation-1");
        rejected.setProgramId(10L);
        rejected.setUserId(20L);
        rejected.setRequestId("request-1");
        rejected.setOrderNumber(1001L);
        rejected.setRequestFingerprint("fingerprint-1");
        rejected.setResultStatus("REJECTED");
        rejected.setRejectCode("INVALID_RESERVATION");
        when(fixture.requestMapper.selectOne(any())).thenReturn(rejected);

        assertThat(fixture.service.recordRejected(message, "INVALID_RESERVATION").getResultStatus())
                .isEqualTo("REJECTED");

        verify(fixture.transitionEventService, never()).enqueue(anyLong(), anyLong(), anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    /** 两座订单，且故意按 seatId 倒序传入，用来验证批量锁座前会重新排序。 */
    private static OrderCreateEvent twoSeatMessage() {
        Date now = new Date();
        OrderCreateEvent message = message();
        message.setOrderPrice(new BigDecimal("256.00"));
        message.setOrderTicketUserCreateDtoList(List.of(
                ticket(32L, 22L, "1排2座", now),
                ticket(31L, 21L, "1排1座", now)));
        return message;
    }

    private static OrderTicketUserCreateDto ticket(long seatId, long ticketUserId, String seatInfo, Date now) {
        OrderTicketUserCreateDto ticket = new OrderTicketUserCreateDto();
        ticket.setOrderNumber(1001L);
        ticket.setProgramId(10L);
        ticket.setUserId(20L);
        ticket.setTicketUserId(ticketUserId);
        ticket.setSeatId(seatId);
        ticket.setSeatInfo(seatInfo);
        ticket.setTicketCategoryId(41L);
        ticket.setOrderPrice(new BigDecimal("128.00"));
        ticket.setCreateOrderTime(now);
        return ticket;
    }

    private static OrderCreateEvent message() {
        Date now = new Date();
        OrderTicketUserCreateDto ticket = ticket(31L, 21L, "1排1座", now);

        OrderCreateEvent message = new OrderCreateEvent();
        message.setIntentId("reservation-1");
        message.setRequestId("request-1");
        message.setRequestFingerprint("fingerprint-1");
        message.setOrderNumber(1001L);
        message.setProgramId(10L);
        message.setProgramPermitChooseSeat(1);
        message.setUserId(20L);
        message.setAccountLimit(6);
        message.setSaleVersion("sale-v1");
        message.setCreateOrderTime(now);
        message.setReservationExpireTime(new Date(now.getTime() + 60_000));
        message.setOrderPrice(new BigDecimal("128.00"));
        message.setOrderTicketUserCreateDtoList(List.of(ticket));
        return message;
    }

    private static final class Fixture {
        private final OrderRequestMapper requestMapper = mock(OrderRequestMapper.class);
        private final AccountProgramPurchaseMapper purchaseMapper = mock(AccountProgramPurchaseMapper.class);
        private final TradeSeatInventoryMapper inventoryMapper = mock(TradeSeatInventoryMapper.class);
        private final OrderMapper orderMapper = mock(OrderMapper.class);
        private final OrderTicketUserMapper ticketMapper = mock(OrderTicketUserMapper.class);
        private final OrderProgramMapper orderProgramMapper = mock(OrderProgramMapper.class);
        private final ReservationTransitionEventService transitionEventService =
                mock(ReservationTransitionEventService.class);
        private final UidGenerator uidGenerator = mock(UidGenerator.class);
        private final TradeOrderCreateService service = new TradeOrderCreateService(requestMapper, purchaseMapper,
                inventoryMapper, orderMapper, ticketMapper, orderProgramMapper,
                transitionEventService, uidGenerator);
    }
}
