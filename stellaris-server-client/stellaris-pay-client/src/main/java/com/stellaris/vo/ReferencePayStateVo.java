package com.stellaris.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/** 对账所需的最小支付账单事实。 */
@Data
public class ReferencePayStateVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String outOrderNo;
    private Integer payBillStatus;
    private BigDecimal payAmount;
}
