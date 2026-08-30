package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: api调用记录 实体
 * @author: xz_y
 **/
@Data
@TableName("d_api_data")
public class ApiData extends BaseTableData implements Serializable {
    
    private Long id;
    
    private String headVersion;
    
    private String apiAddress;
    
    private String apiMethod;
    
    private String apiBody;
    
    private String apiParams;
    
    private String apiUrl;
    
    private String callDayTime;
    
    private String callHourTime;
    
    private String callMinuteTime;
    
    private String callSecondTime;
    
    private Integer type;
}
