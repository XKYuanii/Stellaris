package com.stellaris.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RecordOrderTickerUserManageVo {
    
    private Long ticketUserOrderId;
    
    private Long ticketUserId;
    
    private Long seatId;
    
    private String seatInfo;
    
    private String redisBeforeSeatStatusName;
    
    private String redisAfterSeatStatusName;
    
    private Long ticketCategoryId;
    
    private String ticketCategoryName;
    
    private BigDecimal orderPrice;
    
    private Integer dbRecordTypeCode;
    
    private String dbRecordTypeValue;
    
    private String dbRecordTypeName;
    
    private String redisRecordTypeName;
    
    private Integer reconciliationStatus;
    
    private String reconciliationStatusName;
    
    
}
