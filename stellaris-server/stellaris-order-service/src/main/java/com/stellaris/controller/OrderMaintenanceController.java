package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.OrderGetDto;
import com.stellaris.dto.OrderSimpleListDto;
import com.stellaris.service.OrderService;
import com.stellaris.service.kafka.OrderCreateDltService;
import com.stellaris.service.reference.ReservationTransitionEventService;
import com.stellaris.vo.OrderListVo;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Manual maintenance endpoints. Disabled unless an operator explicitly enables them. */
@RestController
@RequestMapping("/order")
@ConditionalOnProperty(prefix = "stellaris.management.operations", name = "enabled", havingValue = "true")
public class OrderMaintenanceController {
    private final OrderService orderService;
    private final ReservationTransitionEventService reservationTransitionEventService;
    private final OrderCreateDltService orderCreateDltService;

    public OrderMaintenanceController(OrderService orderService,
                                      ReservationTransitionEventService reservationTransitionEventService,
                                      OrderCreateDltService orderCreateDltService) {
        this.orderService = orderService;
        this.reservationTransitionEventService = reservationTransitionEventService;
        this.orderCreateDltService = orderCreateDltService;
    }

    @Operation(summary = "查看缓存中的订单（运维）")
    @PostMapping("/get/cache")
    public ApiResponse<String> getCache(@Valid @RequestBody OrderGetDto dto) {
        return ApiResponse.ok(orderService.getCache(dto));
    }

    @Operation(summary = "按订单编号或用户查询订单（运维）")
    @PostMapping("/simple/list")
    public ApiResponse<List<OrderListVo>> simpleList(@Valid @RequestBody OrderSimpleListDto dto) {
        return ApiResponse.ok(orderService.simpleList(dto));
    }

    @Operation(summary = "重放失败的 v5 支付/取消座位迁移命令（运维）")
    @PostMapping("/reservation/transition/replay")
    public ApiResponse<Boolean> replayReservationTransition(@RequestParam long orderNumber,
                                                             // Maintenance lookup key, not an authenticated identity.
                                                             @RequestParam long userId) {
        return ApiResponse.ok(reservationTransitionEventService.replay(orderNumber, userId));
    }

    @Operation(summary = "按原 eventId/orderNumber 重放创建订单 DLT（运维）")
    @PostMapping("/create/dlt/replay")
    public ApiResponse<Boolean> replayCreateOrderDlt(@RequestParam long orderNumber,
                                                      // Maintenance lookup key, not an authenticated identity.
                                                      @RequestParam long userId) {
        return ApiResponse.ok(orderCreateDltService.replay(orderNumber, userId));
    }
}
