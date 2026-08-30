package com.stellaris.pay;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付状态查询 实体
 * @author: xz_y
 **/
@Data
public class TradeResult {
    
    private boolean success;
    
    private Integer payBillStatus;
    
    private String outTradeNo;
    
    private BigDecimal totalAmount;
}
