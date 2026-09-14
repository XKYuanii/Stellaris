package com.stellaris.domain;

/** Shared Redis key contract for the Program admission writer and Order terminal-state writer. */
public final class OrderReservationRedisKeys {
    private OrderReservationRedisKeys() {
    }

    private static String prefix(long programId) {
        return "stellaris:{sale:" + OrderReservationStreamKeys.shard(programId)
                + "}:program:" + programId + ":seat:";
    }

    public static String meta(long programId) { return prefix(programId) + "meta"; }
    public static String available(long programId, long ticketCategoryId) {
        return prefix(programId) + "available:" + ticketCategoryId;
    }
    public static String owner(long programId) { return prefix(programId) + "owner"; }
    public static String reservation(long programId) { return prefix(programId) + "reservation"; }
    public static String accountCount(long programId) { return prefix(programId) + "account-count"; }
    public static String result(long programId) { return prefix(programId) + "reservation:result"; }
    public static String sold(long programId) { return prefix(programId) + "sold"; }
    public static String finalState(long programId) { return prefix(programId) + "reservation:final"; }
    public static String expiration(long programId) { return prefix(programId) + "reservation:expiration"; }
    public static String eventStream(long programId) {
        return OrderReservationStreamKeys.stream(OrderReservationStreamKeys.shard(programId));
    }
}
