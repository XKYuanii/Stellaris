package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.NotifyDto;
import com.stellaris.dto.PayBillDto;
import com.stellaris.dto.PayDto;
import com.stellaris.dto.RefundDto;
import com.stellaris.dto.TradeCheckDto;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.service.PayService;
import com.stellaris.service.reference.ReferencePayStateQueryService;
import com.stellaris.vo.NotifyVo;
import com.stellaris.vo.PayBillVo;
import com.stellaris.vo.PayResultVo;
import com.stellaris.vo.TradeCheckVo;
import com.stellaris.vo.ReferencePayStateVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/pay")
@Tag(name = "pay", description = "支付")
public class PayController {
    
    @Autowired
    private PayService payService;

    @Autowired
    private ReferencePayStateQueryService referencePayStateQueryService;
    
    @Operation(summary  = "支付（feign微服务调用，不直接暴漏给前端）")
    @PostMapping(value = "/common/pay")
    public ApiResponse<PayResultVo> commonPay(@Valid @RequestBody PayDto payDto) {
        return ApiResponse.ok(payService.commonPay(payDto));
    }
    
    @Operation(summary  = "支付后回调通知（feign微服务调用，不直接暴漏给前端）")
    @PostMapping(value = "/notify")
    public ApiResponse<NotifyVo> notify(@Valid @RequestBody NotifyDto notifyDto) {
        return ApiResponse.ok(payService.notify(notifyDto));
    }
    
    @Operation(summary  = "支付状态查询")
    @PostMapping(value = "/trade/check")
    public ApiResponse<TradeCheckVo> tradeCheck(@Valid @RequestBody TradeCheckDto tradeCheckDto) {
        return ApiResponse.ok(payService.tradeCheck(tradeCheckDto));
    }
    
    @Operation(summary  = "退款")
    @PostMapping(value = "/refund")
    public ApiResponse<String> refund(@Valid @RequestBody RefundDto refundDto) {
        return ApiResponse.ok(payService.refund(refundDto));
    }

    @Operation(summary = "v5 对账内部：批量查询支付账单状态")
    @PostMapping(value = "/reference/reconciliation/state/batch")
    public ApiResponse<List<ReferencePayStateVo>> referenceStateBatch(@RequestBody ReferencePayStateQueryDto dto) {
        return ApiResponse.ok(referencePayStateQueryService.query(dto));
    }
    
    @Operation(summary  = "账单详情查询")
    @PostMapping(value = "/detail")
    public ApiResponse<PayBillVo> detail(@Valid @RequestBody PayBillDto payBillDto) {
        return ApiResponse.ok(payService.detail(payBillDto));
    }
}
