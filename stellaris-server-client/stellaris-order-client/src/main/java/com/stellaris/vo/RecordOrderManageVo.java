package com.stellaris.vo;

import lombok.Data;

import java.util.List;

@Data
public class RecordOrderManageVo {
    
    private Long programId;
    
    private Long orderNumber;
    
    private Long userId;
    
    private Integer reconciliationStatus;
    
    private String reconciliationStatusName;
    
    private List<RecordOrderTickerUserManageVo> recordOrderTickerUserManageVoList; 
}
