package com.stellaris.vo;

import lombok.Data;

import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 深度规则 返回vo
 * @author: xz_y
 **/
@Data
public class DepthRuleVo {
    
    private String id;
    
    private String startTimeWindow;
    
    private long startTimeWindowTimestamp;
    
    private String endTimeWindow;
    
    private long endTimeWindowTimestamp;
    
    private Integer statTime;
    
    private Integer statTimeType;
    
    private Integer threshold;
    
    private Integer effectiveTime;
    
    private Integer effectiveTimeType;
    
    private String limitApi;
    
    private String message;
    
    private Integer status;
    
    private Date createTime;
}
