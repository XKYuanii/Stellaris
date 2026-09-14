package com.stellaris.service.reference;

import java.util.List;

/** 已完成候选选择后的原子锁座请求。Lua 只处理这些有限候选，不扫描场馆。 */
public record SeatReservationRequest(String intentId, long programId, long userId, int accountLimit,
                                     String requestFingerprint, String eventPayload, long expireAtMillis,
                                     long receiptTtlMillis, long maxStreamLength,
                                     List<Seat> seats) {

    public record Seat(long seatId, long ticketCategoryId, long priceInCents, long ticketUserId) {
    }
}
