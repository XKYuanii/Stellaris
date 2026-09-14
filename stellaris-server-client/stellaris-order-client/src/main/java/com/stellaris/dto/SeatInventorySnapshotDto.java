package com.stellaris.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/** Program 发布给交易库的单个座位销售快照。 */
@Data
public class SeatInventorySnapshotDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long seatId;
    private Long ticketCategoryId;
    private Long priceInCents;
    /** 1=AVAILABLE, 2=LOCKED, 3=SOLD. Initialize accepts AVAILABLE/SOLD; rebuild returns all three. */
    private Integer sellStatus;
    /** Fields below are populated by the authoritative rebuild query for LOCKED/SOLD seats. */
    private String reservationId;
    private Long orderNumber;
    private Long userId;
    private Long ticketUserId;
    private String requestFingerprint;
    private Date reservationExpireTime;
}
