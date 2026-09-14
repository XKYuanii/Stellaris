package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.SeatInventoryInitializeDto;
import com.stellaris.dto.SeatInventoryCountDto;
import com.stellaris.dto.SeatInventoryQueryDto;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.vo.AccountOrderCountVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单服务 feign 异常
 * @author: xz_y
 **/
@Component
public class OrderClientFallback implements OrderClient {
    
    @Override
    public ApiResponse<AccountOrderCountVo> accountOrderCount(final AccountOrderCountDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<Boolean> initializeSeatInventory(SeatInventoryInitializeDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<SeatInventorySnapshotDto>> currentSeatInventory(SeatInventoryQueryDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<Long> availableSeatCount(SeatInventoryCountDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
