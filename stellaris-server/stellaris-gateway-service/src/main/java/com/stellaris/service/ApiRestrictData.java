package com.stellaris.service;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 接口请求记录 实体对象
 * @author: 阿星不是程序员
 **/
@Data
public class ApiRestrictData {

    private Long triggerResult;
    
    private Long triggerCallStat;
    
    private Long apiCount;
    
    private Long threshold;
    
    private Long messageIndex;
}
