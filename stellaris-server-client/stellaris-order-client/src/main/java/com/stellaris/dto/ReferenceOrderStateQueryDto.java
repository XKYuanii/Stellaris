package com.stellaris.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** v5 对账内部接口：按业务订单号批量读取订单事实。 */
@Data
public class ReferenceOrderStateQueryDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private List<Long> orderNumbers;
}
