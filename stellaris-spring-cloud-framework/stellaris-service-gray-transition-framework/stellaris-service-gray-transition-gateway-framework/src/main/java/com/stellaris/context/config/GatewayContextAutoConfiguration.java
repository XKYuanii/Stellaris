package com.stellaris.context.config;

import com.stellaris.context.ContextHandler;
import com.stellaris.context.filter.GatewayWorkClearFilter;
import com.stellaris.context.filter.GatewayWorkRouteFilter;
import com.stellaris.context.impl.GatewayContextHandler;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: Gateway配置
 * @author: 阿星不是程序员
 **/
@Configuration(proxyBeanMethods = false)
public class GatewayContextAutoConfiguration {
    
    @Bean
    public GlobalFilter gatewayWorkRouteFilter() {
        return new GatewayWorkRouteFilter();
    }
    
    @Bean
    public GlobalFilter gatewayWorkClearFilter() {
        return new GatewayWorkClearFilter();
    }
    
    @Bean
    public ContextHandler webMvcContext(){
        return new GatewayContextHandler();
    }
}
