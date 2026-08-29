package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.OrderCreateDto;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.vo.AccountOrderCountVo;
import com.stellaris.vo.ReferenceOrderStateVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单服务 feign 异常
 * @author: 阿星不是程序员
 **/
@Component
public class OrderClientFallback implements OrderClient {
    
    @Override
    public ApiResponse<String> create(final OrderCreateDto orderCreateDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<AccountOrderCountVo> accountOrderCount(final AccountOrderCountDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<Void> reloadRouteMappingCache() {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<ReferenceOrderStateVo>> referenceStateBatch(ReferenceOrderStateQueryDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
