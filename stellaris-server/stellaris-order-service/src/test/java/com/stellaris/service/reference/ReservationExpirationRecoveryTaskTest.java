package com.stellaris.service.reference;

import com.stellaris.entity.Order;
import com.stellaris.entity.OrderRequest;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderRequestMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.OrderService;
import com.stellaris.service.trade.TradeOrderCreateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationExpirationRecoveryTaskTest {

    @Test
    void orphanReservationIsReleasedAfterTheGraceWindow() {
        Fixture fixture = new Fixture();
        when(fixture.requestMapper.selectOne(any())).thenReturn(null);

        fixture.task.recoverOne("12|intent-12");

        verify(fixture.transitionService).release(12L, "intent-12");
    }

    @Test
    void paidOrderIsConfirmedAndNeverReleased() {
        Fixture fixture = new Fixture();
        when(fixture.requestMapper.selectOne(any())).thenReturn(request("intent-12", 1001L));
        when(fixture.orderMapper.selectOne(any())).thenReturn(order(1001L, OrderStatus.PAY,
                new Date(System.currentTimeMillis() - 60_000)));

        fixture.task.recoverOne("12|intent-12");

        verify(fixture.transitionService).confirmSale(12L, "intent-12");
        verify(fixture.transitionService, never()).release(anyLong(), anyString());
    }

    @Test
    void redisClockCannotCancelAnOrderBeforeTheMysqlDeadline() {
        Fixture fixture = new Fixture();
        when(fixture.requestMapper.selectOne(any())).thenReturn(request("intent-12", 1001L));
        when(fixture.orderMapper.selectOne(any())).thenReturn(order(1001L, OrderStatus.NO_PAY,
                new Date(System.currentTimeMillis() + 60_000)));

        assertThatIllegalStateException().isThrownBy(() -> fixture.task.recoverOne("12|intent-12"))
                .withMessageContaining("deadline");

        verify(fixture.orderService, never()).updateOrderRelatedData(any(), any());
        verify(fixture.transitionService, never()).release(anyLong(), anyString());
    }

    @Test
    void failedHeadMemberIsRescoredSoLaterReservationsCanProgress() {
        Fixture fixture = new Fixture();
        String index = "stellaris:{sale:0}:reservation:expiration:index";
        when(fixture.redisTemplate.opsForZSet().rangeByScore(
                eq(index), anyDouble(), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(Set.of("12|intent-12"));
        when(fixture.requestMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(fixture.transitionService).release(12L, "intent-12");
        ReflectionTestUtils.setField(fixture.task, "graceMs", 0L);
        ReflectionTestUtils.setField(fixture.task, "retryBackoffMs", 30_000L);
        ReflectionTestUtils.setField(fixture.task, "batchSize", 100);

        fixture.task.recover();

        verify(fixture.redisTemplate.opsForZSet()).add(
                eq(index), eq("12|intent-12"), anyDouble());
    }

    private OrderRequest request(String intentId, long orderNumber) {
        OrderRequest request = new OrderRequest();
        request.setReservationId(intentId);
        request.setOrderNumber(orderNumber);
        request.setResultStatus("CREATED");
        return request;
    }

    private Order order(long orderNumber, OrderStatus status, Date expireTime) {
        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setProgramId(12L);
        order.setIntentId("intent-12");
        order.setOrderStatus(status.getCode());
        order.setExpireTime(expireTime);
        return order;
    }

    private static final class Fixture {
        private final RedisCache redisCache = mock(RedisCache.class);
        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        private final OrderRequestMapper requestMapper = mock(OrderRequestMapper.class);
        private final OrderMapper orderMapper = mock(OrderMapper.class);
        private final TradeOrderCreateService createService = mock(TradeOrderCreateService.class);
        private final OrderService orderService = mock(OrderService.class);
        private final OrderReservationReleaseService transitionService = mock(OrderReservationReleaseService.class);
        private final ReservationExpirationRecoveryTask task;

        private Fixture() {
            when(redisCache.getInstance()).thenReturn(redisTemplate);
            task = new ReservationExpirationRecoveryTask(redisCache, requestMapper, orderMapper,
                    createService, orderService, transitionService, new SimpleMeterRegistry());
        }
    }
}
