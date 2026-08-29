package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 创建订单引起的库存操作幂等记录。 */
@Data
@TableName("d_order_inventory_operation")
public class OrderInventoryOperation extends BaseTableData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long orderNumber;
    private Long eventId;
    private Long programId;
}
