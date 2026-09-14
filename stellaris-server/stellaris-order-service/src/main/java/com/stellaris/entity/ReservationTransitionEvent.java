package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/** 订单终态提交后同步 Redis 展示状态的本地可靠命令。 */
@Data
@TableName("d_reservation_transition_event")
public class ReservationTransitionEvent extends BaseTableData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long commandId;
    private Long orderNumber;
    private Long userId;
    private Long programId;
    private String intentId;
    private Integer targetSellStatus;
    private String payload;
    /** PENDING/PROCESSING/FAILED/SUCCEEDED/DEAD。 */
    private String eventStatus;
    private Integer retryCount;
    private Date nextRetryTime;
    private Date lastAttemptTime;
    private String lastError;
}
