package com.stellaris.service.trade;

import com.stellaris.entity.Order;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderExpiryCloseTaskTest {

    @Test
    void permanentlyFailingHeadPageIsDeferredBeforeTheNextPageIsRead() {
        OrderMapper mapper = mock(OrderMapper.class);
        OrderService orderService = mock(OrderService.class);
        Order failed = order(1L, 1001L);
        Order later = order(2L, 1002L);
        when(mapper.selectExpiredForClose(any(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(failed), List.of(later), List.of());
        when(mapper.deferExpiryClose(anyLong(), any(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("database dependency unavailable"))
                .when(orderService).updateOrderRelatedData(1001L, com.stellaris.enums.OrderStatus.CANCEL);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderExpiryCloseTask task = new OrderExpiryCloseTask(mapper, orderService, registry);
        ReflectionTestUtils.setField(task, "batchSize", 1);
        ReflectionTestUtils.setField(task, "maxBatchesPerRun", 3);
        ReflectionTestUtils.setField(task, "partitionCount", 1);
        ReflectionTestUtils.setField(task, "partitionIndex", 0);
        ReflectionTestUtils.setField(task, "retryBackoffMs", 60_000L);

        task.closeExpiredOrders();

        verify(mapper).deferExpiryClose(anyLong(), any(), any(), any());
        verify(orderService).updateOrderRelatedData(1002L, com.stellaris.enums.OrderStatus.CANCEL);
        verify(mapper, times(3)).selectExpiredForClose(any(), anyInt(), anyInt(), anyInt(), anyInt());
        assertThat(registry.counter("stellaris_order_expiry_close_total",
                "result", "deferred").count()).isEqualTo(1.0);
    }

    private Order order(long id, long orderNumber) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber(orderNumber);
        order.setExpireTime(new Date(System.currentTimeMillis() - 60_000));
        return order;
    }
}
