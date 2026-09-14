package com.stellaris.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SeatInventoryQueryDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long programId;
}
