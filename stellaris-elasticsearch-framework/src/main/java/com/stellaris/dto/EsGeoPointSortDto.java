package com.stellaris.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: elasticsearch GeoPoint定位
 * @author: xz_y
 **/
@Data
public class EsGeoPointSortDto {
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
