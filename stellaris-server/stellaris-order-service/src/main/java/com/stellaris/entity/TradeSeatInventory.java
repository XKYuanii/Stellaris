package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 交易库内的唯一座位销售事实；布局信息仍归 Program。 */
@Data
@TableName("t_seat_inventory")
public class TradeSeatInventory extends BaseTableData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long programId;
    private Long seatId;
    private Long ticketCategoryId;
    private Integer sellStatus;
    private String reservationId;
    private Long orderNumber;
    private Long version;
    private Long priceInCents;
    private String saleVersion;
}
