package com.stellaris.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 座位id和购票人id
 * @author: xz_y
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatIdAndTicketUserIdDomain {

    private Long seatId;
    
    private Long ticketUserId;
}
