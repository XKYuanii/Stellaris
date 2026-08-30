package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 普通规则 实体
 * @author: xz_y
 **/
@Data
@TableName("d_rule")
public class Rule extends BaseTableData implements Serializable {
    
    private Long id;

    private Integer statTime;
    
    private Integer statTimeType;
    
    private Integer threshold;
    
    private Integer effectiveTime;
    
    private Integer effectiveTimeType;
    
    private String limitApi;
    
    private String message;
}
