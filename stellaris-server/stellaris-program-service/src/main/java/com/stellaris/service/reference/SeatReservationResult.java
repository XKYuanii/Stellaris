package com.stellaris.service.reference;

import com.stellaris.vo.SeatVo;

import java.util.List;

public record SeatReservationResult(boolean success, boolean replayed, String code, List<SeatVo> seats) {
    public boolean retryableSeatConflict() {
        return "SEAT_UNAVAILABLE".equals(code) || "SEAT_OWNED".equals(code);
    }
}
