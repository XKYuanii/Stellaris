package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

/** 在调用支付渠道前创建的退款事实记录，refund_no 是稳定渠道幂等号。 */
@Data
@TableName("d_refund_intent")
public class RefundIntent extends BaseTableData {
    private Long id;
    private String refundNo;
    private String outOrderNo;
    private Long payBillId;
    private BigDecimal amount;
    private String channel;
    private String reason;
    private String intentStatus;
    private Integer retryCount;
    private Date nextRetryTime;
    private String lastError;
}
