package com.stellaris.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 开售前将节目静态布局发布成交易库销售库存。 */
@Data
public class SeatInventoryInitializeDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long programId;
    private String saleVersion;
    private List<SeatInventorySnapshotDto> seats;
}
