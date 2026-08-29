package com.stellaris.entity;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 购票人订单聚合统计 实体
 * @author: 阿星不是程序员
 **/
@Data
public class OrderTicketUserAggregate {
    
    private Long orderNumber;
    
    private Integer orderTicketUserCount;
}
