package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.domain.ReconciliationTaskData;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.OrderCancelDto;
import com.stellaris.dto.OrderCreateDto;
import com.stellaris.dto.OrderGetDto;
import com.stellaris.dto.OrderListDto;
import com.stellaris.dto.OrderPayCheckDto;
import com.stellaris.dto.OrderPayDto;
import com.stellaris.dto.OrderSimpleListDto;
import com.stellaris.dto.ProgramGetDto;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.properties.ApiVerify;
import com.stellaris.scheduletask.PresentationOrderDataTask;
import com.stellaris.scheduletask.ReconciliationTask;
import com.stellaris.service.OrderService;
import com.stellaris.service.OrderTaskService;
import com.stellaris.service.reference.ReferenceOrderStateQueryService;
import com.stellaris.service.reference.ReservationTransitionEventService;
import com.stellaris.service.kafka.OrderCreateDltService;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/order")
@Tag(name = "order", description = "订单")
public class OrderController {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private OrderTaskService orderTaskService;
    
    @Autowired
    private ReconciliationTask reconciliationTask;
    
    @Autowired
    private PresentationOrderDataTask orderDataTask;
    
    @Autowired
    private ApiVerify apiVerify;

    @Autowired
    private ReferenceOrderStateQueryService referenceOrderStateQueryService;

    @Autowired
    private ReservationTransitionEventService reservationTransitionEventService;

    @Autowired
    private OrderCreateDltService orderCreateDltService;
    
    
    @Operation(summary  = "订单创建(不提供给前端调用，只允许内部program服务调用)")
    @PostMapping(value = "/create")
    public ApiResponse<String> create(@Valid @RequestBody OrderCreateDto orderCreateDto) {
        return ApiResponse.ok(orderService.create(orderCreateDto));
    }

    @Operation(summary = "v5 对账内部：批量查询订单状态")
    @PostMapping("/reference/reconciliation/state/batch")
    public ApiResponse<List<ReferenceOrderStateVo>> referenceStateBatch(@RequestBody ReferenceOrderStateQueryDto dto) {
        return ApiResponse.ok(referenceOrderStateQueryService.query(dto));
    }
    
    @Operation(summary  = "订单支付")
    @PostMapping(value = "/pay")
    public ApiResponse<PayResultVo> pay(@Valid @RequestBody OrderPayDto orderPayDto) {
        return ApiResponse.ok(orderService.pay(orderPayDto));
    }
    
    @Operation(summary  = "订单支付后状态检查")
    @PostMapping(value = "/pay/check")
    public ApiResponse<OrderPayCheckVo> payCheck(@Valid @RequestBody OrderPayCheckDto orderPayCheckDto) {
        return ApiResponse.ok(orderService.payCheck(orderPayCheckDto));
    }
    
    @Operation(summary  = "支付宝支付后回调通知")
    @PostMapping(value = "/alipay/notify")
    public String alipayNotify(HttpServletRequest request) {
        return orderService.alipayNotify(request);
    }
    
    @Operation(summary  = "查看订单列表")
    @PostMapping(value = "/select/list")
    public ApiResponse<List<OrderListVo>> selectList(@Valid @RequestBody OrderListDto orderListDto) {
        return ApiResponse.ok(orderService.selectList(orderListDto));
    }
    
    @Operation(summary  = "查看订单详情")
    @PostMapping(value = "/get")
    public ApiResponse<OrderGetVo> get(@Valid @RequestBody OrderGetDto orderGetDto) {
        return ApiResponse.ok(orderService.get(orderGetDto));
    }
    
    @Operation(summary  = "账户下某个节目的订单数量(不提供给前端调用，只允许内部program服务调用)")
    @PostMapping(value = "/account/order/count")
    public ApiResponse<AccountOrderCountVo> accountOrderCount(@Valid @RequestBody AccountOrderCountDto accountOrderCountDto) {
        return ApiResponse.ok(orderService.accountOrderCount(accountOrderCountDto));
    }
    
    @Operation(summary  = "查看缓存中的订单")
    @PostMapping(value = "/get/cache")
    public ApiResponse<String> getCache(@Valid @RequestBody OrderGetDto orderGetDto) {
        return ApiResponse.ok(orderService.getCache(orderGetDto));
    }
    
    @Operation(summary  = "订单详情取消")
    @PostMapping(value = "/cancel")
    public ApiResponse<Boolean> cancel(@Valid @RequestBody OrderCancelDto orderCancelDto) {
        return ApiResponse.ok(orderService.initiateCancel(orderCancelDto));
    }

    @Operation(summary  = "对账任务执行")
    @PostMapping(value = "/reconciliation/task")
    public ApiResponse<ReconciliationTaskData> reconciliationTask(@Valid @RequestBody ProgramGetDto programGetDto) {
        apiVerify.verifyApi();
        return ApiResponse.ok(orderTaskService.reconciliationTask(programGetDto.getId()));
    }
    
    @Operation(summary  = "对账任务执行(全部)")
    @PostMapping(value = "/reconciliation/task/all")
    public ApiResponse<ReconciliationTaskData> reconciliationTaskAll() {
        apiVerify.verifyApi();
        reconciliationTask.reconciliationTask();
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "通过订单编号或者用户id查询订单列表")
    @PostMapping(value = "/simple/list")
    public ApiResponse<List<OrderListVo>> simpleList(@Valid @RequestBody OrderSimpleListDto orderSimpleListDto) {
        return ApiResponse.ok(orderService.simpleList(orderSimpleListDto));
    }
    
    @Operation(summary  = "测试")
    @PostMapping(value = "/test")
    public ApiResponse<Void> test() {
        orderDataTask.executeTask();
        return ApiResponse.ok();
    }

    @Operation(summary = "重放失败的 v5 支付/取消座位迁移命令")
    @PostMapping(value = "/reservation/transition/replay")
    public ApiResponse<Boolean> replayReservationTransition(@RequestParam long orderNumber,
                                                            @RequestParam long userId) {
        return ApiResponse.ok(reservationTransitionEventService.replay(orderNumber, userId));
    }

    @Operation(summary = "按原 eventId/orderNumber 重放已持久化的创建订单 DLT")
    @PostMapping(value = "/create/dlt/replay")
    public ApiResponse<Boolean> replayCreateOrderDlt(@RequestParam long orderNumber, @RequestParam long userId) {
        return ApiResponse.ok(orderCreateDltService.replay(orderNumber, userId));
    }
}
