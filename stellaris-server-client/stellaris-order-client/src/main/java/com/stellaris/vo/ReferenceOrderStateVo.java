package com.stellaris.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/** 对账所需的最小订单事实，避免跨服务返回完整订单和实名信息。 */
@Data
public class ReferenceOrderStateVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private Long orderNumber;
    private Long programId;
    private Integer orderStatus;
    private Date createOrderTime;
}
