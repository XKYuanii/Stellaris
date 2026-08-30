package com.stellaris.domain;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis的操作记录(座位层)
 * @author: xz_y
 **/
@Data
public class SeatRecord {
    
    private Long ticketCategoryId;
    private Long seatId;
    private Long ticketUserId;
    private Integer beforeStatus;
    private Integer afterStatus;
}
