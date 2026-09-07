package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.OrderCancelDto;
import com.stellaris.dto.OrderGetDto;
import com.stellaris.dto.OrderListDto;
import com.stellaris.dto.OrderPayCheckDto;
import com.stellaris.dto.OrderPayDto;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.security.CurrentRequestIdentity;
import com.stellaris.service.OrderService;
import com.stellaris.service.reference.ReferenceOrderStateQueryService;
import com.stellaris.vo.AccountOrderCountVo;
import com.stellaris.vo.OrderGetVo;
import com.stellaris.vo.OrderListVo;
import com.stellaris.vo.ReferenceOrderStateVo;
import com.stellaris.vo.OrderPayCheckVo;
import com.stellaris.vo.PayResultVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/order")
@Tag(name = "order", description = "订单")
public class OrderController {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private ReferenceOrderStateQueryService referenceOrderStateQueryService;

    @Autowired
    private CurrentRequestIdentity currentRequestIdentity;
    
    
    @Operation(summary = "v5 对账内部：批量查询订单状态")
    @PostMapping("/reference/reconciliation/state/batch")
    public ApiResponse<List<ReferenceOrderStateVo>> referenceStateBatch(@RequestBody ReferenceOrderStateQueryDto dto) {
        return ApiResponse.ok(referenceOrderStateQueryService.query(dto));
    }
    
    @Operation(summary  = "订单支付")
    @PostMapping(value = "/pay")
    public ApiResponse<PayResultVo> pay(@Valid @RequestBody OrderPayDto orderPayDto) {
        return ApiResponse.ok(orderService.pay(orderPayDto, currentRequestIdentity.requireUserId()));
    }
    
    @Operation(summary  = "订单支付后状态检查")
    @PostMapping(value = "/pay/check")
    public ApiResponse<OrderPayCheckVo> payCheck(@Valid @RequestBody OrderPayCheckDto orderPayCheckDto) {
        return ApiResponse.ok(orderService.payCheck(orderPayCheckDto, currentRequestIdentity.requireUserId()));
    }
    
    @Operation(summary  = "支付宝支付后回调通知")
    @PostMapping(value = "/alipay/notify")
    public String alipayNotify(HttpServletRequest request) {
        return orderService.alipayNotify(request);
    }
    
    @Operation(summary  = "查看订单列表")
    @PostMapping(value = "/select/list")
    public ApiResponse<List<OrderListVo>> selectList(@Valid @RequestBody OrderListDto orderListDto) {
        return ApiResponse.ok(orderService.selectList(orderListDto, currentRequestIdentity.requireUserId()));
    }
    
    @Operation(summary  = "查看订单详情")
    @PostMapping(value = "/get")
    public ApiResponse<OrderGetVo> get(@Valid @RequestBody OrderGetDto orderGetDto) {
        return ApiResponse.ok(orderService.get(orderGetDto, currentRequestIdentity.requireUserId()));
    }
    
    @Operation(summary  = "账户下某个节目的订单数量(不提供给前端调用，只允许内部program服务调用)")
    @PostMapping(value = "/account/order/count")
    public ApiResponse<AccountOrderCountVo> accountOrderCount(@Valid @RequestBody AccountOrderCountDto accountOrderCountDto) {
        return ApiResponse.ok(orderService.accountOrderCount(accountOrderCountDto));
    }
    
    @Operation(summary  = "订单详情取消")
    @PostMapping(value = "/cancel")
    public ApiResponse<Boolean> cancel(@Valid @RequestBody OrderCancelDto orderCancelDto) {
        return ApiResponse.ok(orderService.initiateCancel(orderCancelDto, currentRequestIdentity.requireUserId()));
    }
}
