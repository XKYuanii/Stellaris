package com.stellaris.balance.config;

import com.stellaris.context.ContextHandler;
import com.stellaris.enhance.config.EnhanceLoadBalancerClientConfiguration;
import com.stellaris.enhance.config.EnhanceLoadBalancerClientConfiguration.BlockingSupportConfiguration;
import com.stellaris.enhance.config.EnhanceLoadBalancerClientConfiguration.ReactiveSupportConfiguration;
import com.stellaris.filter.AbstractServerFilter;
import com.stellaris.filter.impl.ServerGrayFilter;
import com.stellaris.fiterbalance.DefaultFilterLoadBalance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 灰度版本选择相关配置
 * @author: xz_y
 **/
@LoadBalancerClients(defaultConfiguration = {EnhanceLoadBalancerClientConfiguration.class, ReactiveSupportConfiguration.class, BlockingSupportConfiguration.class})
public class GrayLoadBalanceAutoConfiguration {
    
    @Bean
    public DefaultFilterLoadBalance defaultFilterLoadBalance(List<AbstractServerFilter> strategyEnabledFilterList){
        return new DefaultFilterLoadBalance(strategyEnabledFilterList);
    }
    
    @Bean
    public AbstractServerFilter serverGrayFilter(ContextHandler contextHandler) {
        return new ServerGrayFilter(contextHandler);
    }
}
