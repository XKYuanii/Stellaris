package com.stellaris.dto;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: elasticsearch数据参数
 * @author: xz_y
 **/
@Data
public class EsDataCreateDto {
    
    /**
     * 字段名
     * */
    private String paramName;
    /**
     * 字段值
     * */
    private Object paramValue;
}
