package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/** Durable intent created before Order invokes the payment service. */
@Data
@TableName("d_payment_reconciliation_event")
public class PaymentReconciliationEvent extends BaseTableData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long orderNumber;
    /** PENDING/PROCESSING/WAITING/FAILED/SUCCEEDED/DEAD. */
    private String eventStatus;
    private Integer retryCount;
    private Date nextRetryTime;
    private Date lastAttemptTime;
    private String lastError;
}
