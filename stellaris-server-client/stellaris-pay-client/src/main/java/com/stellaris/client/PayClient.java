package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.NotifyDto;
import com.stellaris.dto.PayDto;
import com.stellaris.dto.RefundDto;
import com.stellaris.dto.TradeCheckDto;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.vo.NotifyVo;
import com.stellaris.vo.PayResultVo;
import com.stellaris.vo.TradeCheckVo;
import com.stellaris.vo.ReferencePayStateVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付服务 feign
 * @author: 阿星不是程序员
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"pay-service",fallback = PayClientFallback.class)
public interface PayClient {
    /**
     * 支付
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/pay/common/pay")
    ApiResponse<PayResultVo> commonPay(PayDto dto);
    /**
     * 回调
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/pay/notify")
    ApiResponse<NotifyVo> notify(NotifyDto dto);
    /**
     * 查询支付状态
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/pay/trade/check")
    ApiResponse<TradeCheckVo> tradeCheck(TradeCheckDto dto);
    /**
     * 退款
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/pay/refund")
    ApiResponse<String> refund(RefundDto dto);

    /** v5 三层对账的内部支付事实批量查询。 */
    @PostMapping(value = "/pay/reference/reconciliation/state/batch")
    ApiResponse<List<ReferencePayStateVo>> referenceStateBatch(ReferencePayStateQueryDto dto);
}
