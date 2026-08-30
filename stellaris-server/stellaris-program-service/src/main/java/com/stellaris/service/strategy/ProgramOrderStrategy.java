package com.stellaris.service.strategy;

import com.stellaris.dto.ProgramOrderCreateDto;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单策略
 * @author: xz_y
 **/
public interface ProgramOrderStrategy {
    
    /**
     * 创建订单
     * @param programOrderCreateDto 订单参数
     * @return 订单编号
     * */
    String createOrder(ProgramOrderCreateDto programOrderCreateDto);
    
    /**
     * 获取版本号
     * @return 版本号
     * */
    String version();
}
