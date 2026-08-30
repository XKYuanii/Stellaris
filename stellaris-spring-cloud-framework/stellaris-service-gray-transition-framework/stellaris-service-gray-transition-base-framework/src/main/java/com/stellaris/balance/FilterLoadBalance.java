package com.stellaris.balance;

import org.springframework.cloud.client.ServiceInstance;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 负载均衡服务过滤接口
 * @author: xz_y
 **/
public interface FilterLoadBalance {
    
    /**
     * 服务过滤操作
     * @param servers 服务列表
     * */
    void selectServer(List<ServiceInstance> servers);
}
