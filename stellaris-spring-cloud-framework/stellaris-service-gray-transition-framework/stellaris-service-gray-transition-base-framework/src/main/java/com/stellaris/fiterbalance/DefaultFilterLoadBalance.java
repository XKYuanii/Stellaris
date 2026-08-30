package com.stellaris.fiterbalance;


import com.stellaris.balance.FilterLoadBalance;
import com.stellaris.filter.AbstractServerFilter;
import lombok.AllArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 负载均衡服务过滤接口的实现
 * @author: xz_y
 **/
@AllArgsConstructor
public class DefaultFilterLoadBalance implements FilterLoadBalance {

    protected final List<AbstractServerFilter> strategyFilterList;

    @Override
    public void selectServer(List<ServiceInstance> servers) {
        for (AbstractServerFilter strategyEnabledFilter : strategyFilterList) {
            strategyEnabledFilter.filter(servers);
        }
    }
}