package com.stellaris.service;

import com.stellaris.entity.Order;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.enums.OrderStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.service.reference.ReservationTransitionEventService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceStatusCasTest {

    @Test
    void losingCancelCasMustNotUpdateTicketsOrPublishOppositeSeatTransition() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderTicketUserMapper ticketMapper = mock(OrderTicketUserMapper.class);
        ReservationTransitionEventService transitionService = mock(ReservationTransitionEventService.class);
        Order beforeRace = order(70001L, OrderStatus.NO_PAY);
        Order payWinner = order(70001L, OrderStatus.PAY);
        when(orderMapper.selectOne(any())).thenReturn(beforeRace, payWinner);
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket(70001L)));
        when(orderMapper.update(any(), any())).thenReturn(0);
        OrderService service = new OrderService();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "orderTicketUserMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "reservationTransitionEventService", transitionService);

        assertThatThrownBy(() -> service.updateOrderRelatedData(70001L, OrderStatus.CANCEL))
                .isInstanceOf(StellarisFrameException.class);

        verify(ticketMapper, never()).update(any(), any());
        verify(transitionService, never()).enqueue(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private Order order(long orderNumber, OrderStatus status) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber(orderNumber);
        order.setUserId(9L);
        order.setProgramId(10L);
        order.setOrderStatus(status.getCode());
        return order;
    }

    private OrderTicketUser ticket(long orderNumber) {
        OrderTicketUser ticket = new OrderTicketUser();
        ticket.setId(2L);
        ticket.setOrderNumber(orderNumber);
        ticket.setOrderStatus(OrderStatus.NO_PAY.getCode());
        return ticket;
    }
}
