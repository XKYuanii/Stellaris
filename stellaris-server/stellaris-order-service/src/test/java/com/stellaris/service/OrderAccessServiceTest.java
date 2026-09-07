package com.stellaris.service;

import com.stellaris.entity.Order;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAccessServiceTest {
    @Mock
    private OrderMapper orderMapper;

    @Test
    void queriesByBothOrderNumberAndCurrentUser() {
        Order expected = new Order();
        expected.setOrderNumber(10001L);
        expected.setUserId(42L);
        when(orderMapper.selectOwnedOrder(10001L, 42L)).thenReturn(expected);
        OrderAccessService service = new OrderAccessService(orderMapper);

        assertThat(service.requireOwnedOrder(10001L, 42L)).isSameAs(expected);

        verify(orderMapper).selectOwnedOrder(10001L, 42L);
    }

    @Test
    void hidesWhetherAnotherUsersOrderExists() {
        when(orderMapper.selectOwnedOrder(10001L, 99L)).thenReturn(null);
        OrderAccessService service = new OrderAccessService(orderMapper);

        assertThatThrownBy(() -> service.requireOwnedOrder(10001L, 99L))
                .isInstanceOfSatisfying(StellarisFrameException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BaseCode.ORDER_NOT_EXIST.getCode()));
    }
}
