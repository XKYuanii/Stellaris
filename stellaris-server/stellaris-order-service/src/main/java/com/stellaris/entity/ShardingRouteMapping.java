package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分片路由映射 实体
 * @author: xz_y
 **/
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("d_sharding_route_mapping")
public class ShardingRouteMapping extends BaseTableData implements Serializable {
    
    private Long id;
    
    /**
     * 逻辑分片ID（0-1023）
     */
    private Integer logicalShardId;
    
    /**
     * 物理数据库名后缀（0-1，适用于所有库类型）
     */
    private String physicalDatabaseSuffix;
    
    /**
     * 物理表后缀（0-7，适用于所有表类型）
     * 适用于：d_order_{suffix}、d_order_ticket_user_{suffix}、d_order_ticket_user_record_{suffix}
     */
    private Integer physicalTableSuffix;
    
    /**
     * 版本号（用于热更新）
     */
    private Integer version;
}
