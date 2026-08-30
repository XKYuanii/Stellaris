package com.stellaris.domain;

import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis的操作记录
 * @author: xz_y
 **/
@Data
public class ProgramRecord {
    
    private Long timestamp;
    
    private String recordType;
    
    private List<TicketCategoryRecord> ticketCategoryRecordList;
}
