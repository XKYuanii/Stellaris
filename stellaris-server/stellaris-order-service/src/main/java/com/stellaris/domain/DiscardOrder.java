package com.stellaris.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 丢弃的订单
 * @author: xz_y
 **/
@Data
@NoArgsConstructor
public class DiscardOrder {
    /**
     * 参数信息
     * */
    private OrderCreateMq orderCreateMq;
    
    /**
     * 原因
     * */
    private Integer discardOrderReason;
    
    /**
     * 错误信息
     * */
    private String errorMsg;
    
    public DiscardOrder(OrderCreateMq orderCreateMq, Integer discardOrderReason) {
        this.orderCreateMq = orderCreateMq;
        this.discardOrderReason = discardOrderReason;
    }
    
    public DiscardOrder(OrderCreateMq orderCreateMq, Integer discardOrderReason, String errorMsg) {
        this.orderCreateMq = orderCreateMq;
        this.discardOrderReason = discardOrderReason;
        this.errorMsg = errorMsg;
    }
}
