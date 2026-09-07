package com.stellaris.service;

import com.stellaris.entity.Order;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.OrderMapper;
import org.springframework.stereotype.Service;

/** Enforces ownership for user-facing order operations. */
@Service
public class OrderAccessService {
    private final OrderMapper orderMapper;

    public OrderAccessService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public Order requireOwnedOrder(Long orderNumber, Long currentUserId) {
        Order order = orderMapper.selectOwnedOrder(orderNumber, currentUserId);
        if (order == null) {
            // The same response covers both a missing order and an order owned by somebody else.
            throw new StellarisFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        return order;
    }
}
