package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 退款账单 实体
 * @author: 阿星不是程序员
 **/
@Data
@TableName("d_refund_bill")
public class RefundBill extends BaseTableData {

    /**
     * 主键id
     */
    private Long id;

    /** 稳定渠道退款幂等号。 */
    private String refundNo;

    /**
     * 商户订单号
     */
    private String outOrderNo;
    
    /**
     * 账单id
     */
    private Long payBillId;

    /**
     * 退款金额
     */
    private BigDecimal refundAmount;

    /**
     * 账单退款状态 1：未退款 2：已退款
     */
    private Integer refundStatus;

    /**
     * 退款时间
     */
    private Date refundTime;
    
    /**
     * 退款原因
     * */
    private String reason;
}
