package com.stellaris.domain;

import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis的操作记录(票档层)
 * @author: xz_y
 **/
@Data
public class TicketCategoryRecord {
    
    private Long ticketCategoryId;
    private Long beforeAmount;
    private Long afterAmount;
    private Long changeAmount;
    private List<SeatRecord> seatRecordList;
}
