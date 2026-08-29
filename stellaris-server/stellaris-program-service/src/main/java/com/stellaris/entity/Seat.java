package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 座位 实体
 * @author: 阿星不是程序员
 **/
@Data
@TableName("d_seat")
public class Seat extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 节目表id
     */
    private Long programId;
    
    /**
     * 节目票档id
     * */
    private Long ticketCategoryId;
    
    /**
     * 排号
     */
    private Integer rowCode;
    
    /**
     * 列号
     */
    private Integer colCode;

    /**
     * 座位类型 详见seatType枚举
     */
    private Integer seatType;

    /**
     * 座位价格
     */
    private BigDecimal price;

    /**
     * 1未售卖 2锁定 3已售卖
     */
    private Integer sellStatus;

    /** v5 当前预订所有者；用于阻止迟到取消误释放后来订单的座位。 */
    private String reservationId;

    /** 座位状态 CAS 版本，便于审计和后续乐观锁扩展。 */
    private Long seatVersion;
}
