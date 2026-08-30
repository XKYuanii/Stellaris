package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.OrderCreateDto;
import com.stellaris.dto.ReferenceOrderStateQueryDto;
import com.stellaris.vo.AccountOrderCountVo;
import com.stellaris.vo.ReferenceOrderStateVo;
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
     * 创建订单
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping("/order/create")
    ApiResponse<String> create(OrderCreateDto dto);
    
    /**
     * 账户下某个节目的订单数量
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping("/order/account/order/count")
    ApiResponse<AccountOrderCountVo> accountOrderCount(AccountOrderCountDto dto);
    
    /**
     * 重置虚拟分片路由缓存
     * @return 结果
     * */
    @PostMapping(value = "/order/reload/route/mapping/cache")
    ApiResponse<Void> reloadRouteMappingCache();

    /** v5 三层对账的内部订单事实批量查询。 */
    @PostMapping("/order/reference/reconciliation/state/batch")
    ApiResponse<List<ReferenceOrderStateVo>> referenceStateBatch(ReferenceOrderStateQueryDto dto);
}
