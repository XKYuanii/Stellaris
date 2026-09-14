package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.SeatInventoryInitializeDto;
import com.stellaris.dto.SeatInventoryCountDto;
import com.stellaris.dto.SeatInventoryQueryDto;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.vo.AccountOrderCountVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单服务 feign
 * @author: xz_y
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"order-service",fallback = OrderClientFallback.class)
public interface OrderClient {
    
    /**
     * 账户下某个节目的订单数量
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping("/order/interior/account/order/count")
    ApiResponse<AccountOrderCountVo> accountOrderCount(AccountOrderCountDto dto);
    
    /** 开售前幂等发布交易库座位销售快照。 */
    @PostMapping("/order/interior/reference/inventory/initialize")
    ApiResponse<Boolean> initializeSeatInventory(SeatInventoryInitializeDto dto);

    /** Redis 重建时读取交易库权威座位销售状态。 */
    @PostMapping("/order/interior/reference/inventory/current")
    ApiResponse<List<SeatInventorySnapshotDto>> currentSeatInventory(SeatInventoryQueryDto dto);

    @PostMapping("/order/interior/reference/inventory/available/count")
    ApiResponse<Long> availableSeatCount(SeatInventoryCountDto dto);
}
