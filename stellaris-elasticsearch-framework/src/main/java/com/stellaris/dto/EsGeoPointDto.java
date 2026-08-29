package com.stellaris.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: elasticsearch GeoPoint
 * @author: 阿星不是程序员
 **/
@Data
public class EsGeoPointDto {
    /**
     * 字段名
     * */
    private String paramName;
    /**
     * 纬度值
     * */
    private BigDecimal latitude;
    /**
     * 经度值
     * */
    private BigDecimal longitude;
    
    
}
