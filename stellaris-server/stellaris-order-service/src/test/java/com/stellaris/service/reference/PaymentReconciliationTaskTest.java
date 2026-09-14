package com.stellaris.service.reference;

import com.stellaris.client.PayClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.entity.PaymentReconciliationEvent;
import com.stellaris.entity.Order;
import com.stellaris.enums.OrderStatus;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.service.OrderService;
import com.stellaris.vo.ReferencePayStateVo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReconciliationTaskTest {

    @Test
    void paidBillForCancelledOrderIsRefundedWithoutAClientPoll() {
        PayClient payClient = mock(PayClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderService orderService = mock(OrderService.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                mock(PaymentReconciliationEventService.class), payClient, orderMapper, orderService);
        when(orderMapper.selectOne(any())).thenReturn(order(1001L, OrderStatus.CANCEL));
        when(payClient.refund(any())).thenReturn(ApiResponse.ok("refund-1001"));
        when(orderMapper.update(any(), any())).thenReturn(1);

        task.reconcileOne(1001L, payState(PayBillStatus.PAY, "mock", "128.00"));

        verify(payClient).refund(any());
        verify(orderMapper).update(any(), any());
        verify(orderService, never()).updateOrderRelatedData(any(), any());
    }

    @Test
    void paidBillForOpenOrderConvergesThroughTheNormalTransactionalStateMachine() {
        PayClient payClient = mock(PayClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderService orderService = mock(OrderService.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                mock(PaymentReconciliationEventService.class), payClient, orderMapper, orderService);
        when(orderMapper.selectOne(any())).thenReturn(order(1002L, OrderStatus.NO_PAY));

        task.reconcileOne(1002L, payState(PayBillStatus.PAY, "mock", "128.00"));

        verify(orderService).updateOrderRelatedData(1002L, OrderStatus.PAY);
        verify(payClient, never()).refund(any());
    }

    @Test
    void amountMismatchNeverChangesOrderOrRefundsMoney() {
        PayClient payClient = mock(PayClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderService orderService = mock(OrderService.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                mock(PaymentReconciliationEventService.class), payClient, orderMapper, orderService);
        when(orderMapper.selectOne(any())).thenReturn(order(1003L, OrderStatus.CANCEL));

        assertThatExceptionOfType(PaymentReconciliationConflictException.class).isThrownBy(
                () -> task.reconcileOne(1003L, payState(PayBillStatus.PAY, "mock", "127.99")))
                .withMessageContaining("amount");

        verify(orderService, never()).updateOrderRelatedData(any(), any());
        verify(payClient, never()).refund(any());
    }

    @Test
    void missingBillStopsRetryingAfterOrderWasCancelled() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                mock(PaymentReconciliationEventService.class), mock(PayClient.class), orderMapper,
                mock(OrderService.class));
        when(orderMapper.selectOne(any())).thenReturn(order(1004L, OrderStatus.CANCEL));

        task.reconcileMissingBill(1004L);
    }

    @Test
    void missingBillKeepsRetryingWhileOrderCanStillBePaid() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                mock(PaymentReconciliationEventService.class), mock(PayClient.class), orderMapper,
                mock(OrderService.class));
        when(orderMapper.selectOne(any())).thenReturn(order(1005L, OrderStatus.NO_PAY));

        assertThatExceptionOfType(PaymentReconciliationPendingException.class)
                .isThrownBy(() -> task.reconcileMissingBill(1005L))
                .withMessageContaining("not materialized");
    }

    @Test
    void normalUnpaidOrderIsRecordedAsWaitingInsteadOfFailed() {
        PaymentReconciliationEventService eventService = mock(PaymentReconciliationEventService.class);
        PayClient payClient = mock(PayClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                eventService, payClient, orderMapper, mock(OrderService.class));
        com.stellaris.entity.PaymentReconciliationEvent event =
                new com.stellaris.entity.PaymentReconciliationEvent();
        event.setId(7L);
        event.setOrderNumber(1007L);
        when(eventService.claimDue()).thenReturn(List.of(event));
        ReferencePayStateVo state = payState(PayBillStatus.NO_PAY, "mock", "128.00");
        state.setOutOrderNo("1007");
        when(payClient.referenceStateBatch(any())).thenReturn(ApiResponse.ok(List.of(state)));
        when(orderMapper.selectOne(any())).thenReturn(order(1007L, OrderStatus.NO_PAY));

        task.reconcile();

        verify(eventService).waiting(event, "payment is still pending");
        verify(eventService, never()).failed(any(), any());
        verify(eventService, never()).succeeded(any());
    }

    @Test
    void claimedWorkIsSplitIntoChannelSafeRemoteBatches() {
        PaymentReconciliationEventService eventService = mock(PaymentReconciliationEventService.class);
        PayClient payClient = mock(PayClient.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                eventService, payClient, orderMapper, mock(OrderService.class));
        ReflectionTestUtils.setField(task, "remoteBatchSize", 20);
        List<PaymentReconciliationEvent> events = new ArrayList<>();
        for (long orderNumber = 1; orderNumber <= 45; orderNumber++) {
            PaymentReconciliationEvent event = new PaymentReconciliationEvent();
            event.setId(orderNumber);
            event.setOrderNumber(orderNumber);
            events.add(event);
        }
        when(eventService.claimDue()).thenReturn(events);
        when(orderMapper.selectOne(any())).thenReturn(order(1L, OrderStatus.CANCEL));
        when(payClient.referenceStateBatch(any())).thenAnswer(invocation -> {
            ReferencePayStateQueryDto query = invocation.getArgument(0);
            List<ReferencePayStateVo> states = query.getOutOrderNos().stream().map(orderNumber -> {
                ReferencePayStateVo state = payState(PayBillStatus.CANCEL, "mock", "128.00");
                state.setOutOrderNo(orderNumber);
                return state;
            }).toList();
            return ApiResponse.ok(states);
        });

        task.reconcile();

        ArgumentCaptor<ReferencePayStateQueryDto> queries =
                ArgumentCaptor.forClass(ReferencePayStateQueryDto.class);
        verify(payClient, times(3)).referenceStateBatch(queries.capture());
        assertThat(queries.getAllValues()).extracting(query -> query.getOutOrderNos().size())
                .containsExactly(20, 20, 5);
    }

    @Test
    void cancelledOrderDoesNotTrustAnUnverifiedLocalUnpaidBill() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        PaymentReconciliationTask task = new PaymentReconciliationTask(
                mock(PaymentReconciliationEventService.class), mock(PayClient.class), orderMapper,
                mock(OrderService.class));
        when(orderMapper.selectOne(any())).thenReturn(order(1006L, OrderStatus.CANCEL));
        ReferencePayStateVo state = payState(PayBillStatus.NO_PAY, "alipay", "128.00");
        state.setChannelVerified(false);

        assertThatIllegalStateException().isThrownBy(() -> task.reconcileOne(1006L, state))
                .withMessageContaining("not been verified");
    }

    private Order order(long orderNumber, OrderStatus status) {
        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setOrderStatus(status.getCode());
        order.setOrderPrice(new BigDecimal("128.00"));
        return order;
    }

    private ReferencePayStateVo payState(PayBillStatus status, String channel, String amount) {
        ReferencePayStateVo state = new ReferencePayStateVo();
        state.setOutOrderNo("1001");
        state.setPayBillStatus(status.getCode());
        state.setPayChannel(channel);
        state.setPayAmount(new BigDecimal(amount));
        state.setChannelVerified(true);
        return state;
    }
}
