package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.OrderSimpleListDto;
import com.stellaris.service.OrderService;
import com.stellaris.service.stream.OrderStreamFailureService;
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
    private final OrderStreamFailureService orderStreamFailureService;

    public OrderMaintenanceController(OrderService orderService,
                                      ReservationTransitionEventService reservationTransitionEventService,
                                      OrderStreamFailureService orderStreamFailureService) {
        this.orderService = orderService;
        this.reservationTransitionEventService = reservationTransitionEventService;
        this.orderStreamFailureService = orderStreamFailureService;
    }

    @Operation(summary = "按订单编号或用户查询订单（运维）")
    @PostMapping("/simple/list")
    public ApiResponse<List<OrderListVo>> simpleList(@Valid @RequestBody OrderSimpleListDto dto) {
        return ApiResponse.ok(orderService.simpleList(dto));
    }

    @Operation(summary = "重放失败的 v5 支付/取消 Redis 同步命令（运维）")
    @PostMapping("/reservation/transition/replay")
    public ApiResponse<Boolean> replayReservationTransition(@RequestParam long orderNumber,
                                                             // Maintenance lookup key, not an authenticated identity.
                                                             @RequestParam long userId) {
        return ApiResponse.ok(reservationTransitionEventService.replay(orderNumber, userId));
    }

    @Operation(summary = "重放已审计的 Stream 异常记录（运维）")
    @PostMapping("/stream/failure/replay")
    public ApiResponse<Boolean> replayStreamFailure(@RequestParam long id) {
        return ApiResponse.ok(orderStreamFailureService.replay(id));
    }

    @Operation(summary = "安全释放需要人工处理的 Stream 异常预约（运维）")
    @PostMapping("/stream/failure/release")
    public ApiResponse<Boolean> releaseStreamFailureReservation(@RequestParam long id) {
        return ApiResponse.ok(orderStreamFailureService.releaseReservation(id));
    }
}
