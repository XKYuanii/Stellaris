package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.SeatInventoryCountDto;
import com.stellaris.dto.SeatInventoryInitializeDto;
import com.stellaris.dto.SeatInventoryQueryDto;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.service.OrderService;
import com.stellaris.service.trade.TradeInventoryService;
import com.stellaris.vo.AccountOrderCountVo;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 仅供注册中心内服务调用的交易库查询与预热接口。 */
@RestController
@RequestMapping("/order/interior")
public class OrderInteriorController {

    private final OrderService orderService;
    private final TradeInventoryService tradeInventoryService;

    public OrderInteriorController(OrderService orderService, TradeInventoryService tradeInventoryService) {
        this.orderService = orderService;
        this.tradeInventoryService = tradeInventoryService;
    }

    @Operation(summary = "读取账号场次权威已购数量")
    @PostMapping("/account/order/count")
    public ApiResponse<AccountOrderCountVo> accountOrderCount(
            @Valid @RequestBody AccountOrderCountDto dto) {
        return ApiResponse.ok(orderService.accountOrderCount(dto));
    }

    @Operation(summary = "开售前幂等初始化交易库座位库存")
    @PostMapping("/reference/inventory/initialize")
    public ApiResponse<Boolean> initializeSeatInventory(@RequestBody SeatInventoryInitializeDto dto) {
        return ApiResponse.ok(tradeInventoryService.initialize(dto));
    }

    @Operation(summary = "读取交易库权威座位销售状态")
    @PostMapping("/reference/inventory/current")
    public ApiResponse<List<SeatInventorySnapshotDto>> currentSeatInventory(
            @RequestBody SeatInventoryQueryDto dto) {
        if (dto == null || dto.getProgramId() == null) {
            throw new IllegalArgumentException("programId is required");
        }
        return ApiResponse.ok(tradeInventoryService.current(dto.getProgramId()));
    }

    @Operation(summary = "聚合交易库权威可售座位数")
    @PostMapping("/reference/inventory/available/count")
    public ApiResponse<Long> availableSeatCount(@RequestBody SeatInventoryCountDto dto) {
        if (dto == null || dto.getProgramId() == null || dto.getTicketCategoryId() == null) {
            throw new IllegalArgumentException("programId and ticketCategoryId are required");
        }
        return ApiResponse.ok(tradeInventoryService.available(dto.getProgramId(), dto.getTicketCategoryId()));
    }
}
