package com.stellaris.entity;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 购票人订单聚合统计 实体
 * @author: xz_y
 **/
@Data
public class OrderTicketUserAggregate {
    
    private Long orderNumber;
    
    private Integer orderTicketUserCount;
}
