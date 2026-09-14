package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** Redis 受理请求在 MySQL 中的稳定最终结果。 */
@Data
@TableName("t_order_request")
public class OrderRequest {
    private String reservationId;
    private Long programId;
    private Long userId;
    private String requestId;
    private Long orderNumber;
    private String requestFingerprint;
    /** PROCESSING 仅在创建事务内部可见，提交结果只允许 CREATED/REJECTED。 */
    private String resultStatus;
    private String rejectCode;
    private Date createdAt;
    private Date updatedAt;
}
