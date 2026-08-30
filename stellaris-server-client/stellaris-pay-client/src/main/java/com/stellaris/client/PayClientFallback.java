package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.NotifyDto;
import com.stellaris.dto.PayDto;
import com.stellaris.dto.RefundDto;
import com.stellaris.dto.TradeCheckDto;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.vo.NotifyVo;
import com.stellaris.vo.PayResultVo;
import com.stellaris.vo.TradeCheckVo;
import com.stellaris.vo.ReferencePayStateVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付服务 feign 异常
 * @author: xz_y
 **/
@Component
public class PayClientFallback implements PayClient{
    
    @Override
    public ApiResponse<PayResultVo> commonPay(final PayDto payDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<NotifyVo> notify(final NotifyDto notifyDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    

    @Override
    public ApiResponse<TradeCheckVo> tradeCheck(final TradeCheckDto tradeCheckDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<String> refund(final RefundDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<ReferencePayStateVo>> referenceStateBatch(ReferencePayStateQueryDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
