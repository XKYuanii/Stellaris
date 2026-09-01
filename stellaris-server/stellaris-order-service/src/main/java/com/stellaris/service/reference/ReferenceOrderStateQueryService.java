package com.stellaris.service.reference;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.entity.Order;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.vo.ReferenceOrderStateVo;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 仅向 v5 对账暴露订单状态事实，不承担状态修复。 */
@Service
public class ReferenceOrderStateQueryService {
    private static final int MAX_BATCH_SIZE = 500;
    private final OrderMapper orderMapper;

    public ReferenceOrderStateQueryService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public List<ReferenceOrderStateVo> query(ReferenceOrderStateQueryDto dto) {
        List<Long> orderNumbers = dto == null || dto.getOrderNumbers() == null ? List.of()
                : dto.getOrderNumbers().stream().filter(Objects::nonNull).distinct().toList();
        if (orderNumbers.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("reference reconciliation batch exceeds " + MAX_BATCH_SIZE);
        }
        if (orderNumbers.isEmpty()) {
            return List.of();
        }
        return orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                        .in(Order::getOrderNumber, orderNumbers))
                .stream().map(this::toState).toList();
    }

    private ReferenceOrderStateVo toState(Order order) {
        ReferenceOrderStateVo state = new ReferenceOrderStateVo();
        state.setOrderNumber(order.getOrderNumber());
        state.setProgramId(order.getProgramId());
        state.setOrderStatus(order.getOrderStatus());
        state.setCreateOrderTime(order.getCreateOrderTime());
        return state;
    }
}
