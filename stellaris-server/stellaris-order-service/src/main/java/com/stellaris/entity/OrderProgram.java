package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单节目表 实体
 * @author: xz_y
 **/
@Data
@TableName("d_order_program")
public class OrderProgram extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;
    
    /**
     * 节目表id
     */
    private Long programId;
    
    /**
     * 订单编号
     * */
    private Long orderNumber;

    /**
     * 记录id
     */
    private Long identifierId;

    /**
     * 处理状态 1:未处理 2:已处理
     */
    private Integer handleStatus;
}
