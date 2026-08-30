package com.stellaris.vo;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 普通对象 返回vo
 * @author: xz_y
 **/
@Data
public class RuleVo {
    
    private String id;
    
    private Integer statTime;
    
    private Integer statTimeType;
    
    private Integer threshold;
    
    private Integer effectiveTime;
    
    private Integer effectiveTimeType;
    
    private String limitApi;
    
    private String message;
    
    private Integer status;
}
