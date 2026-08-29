package com.stellaris.service.reference;

/** v5/reference 座位键。一个节目所有键使用同一 Hash Tag，满足 Redis Cluster 多键 Lua 约束。 */
public final class SeatReservationKeys {
    /** 固定少量售卖分片：同一节目仍在一个槽，跨节目可分散热点，Relay 也能有界枚举。 */
    public static final int SALE_SHARD_COUNT = 16;

    private SeatReservationKeys() {
    }

    private static String prefix(long programId) {
        return "stellaris:{sale:" + shard(programId) + "}:program:" + programId + ":seat:";
    }

    public static int shard(long programId) {
        return Math.floorMod(programId, SALE_SHARD_COUNT);
    }

    private static String shardPrefix(int shard) {
        if (shard < 0 || shard >= SALE_SHARD_COUNT) {
            throw new IllegalArgumentException("invalid sale shard: " + shard);
        }
        return "stellaris:{sale:" + shard + "}:reservation:event:";
    }

    public static String meta(long programId) {
        return prefix(programId) + "meta";
    }

    public static String available(long programId, long ticketCategoryId) {
        return prefix(programId) + "available:" + ticketCategoryId;
    }

    public static String owner(long programId) {
        return prefix(programId) + "owner";
    }

    public static String reservation(long programId) {
        return prefix(programId) + "reservation";
    }

    public static String accountCount(long programId) {
        return prefix(programId) + "account-count";
    }

    public static String result(long programId) {
        return prefix(programId) + "reservation:result";
    }

    public static String sold(long programId) {
        return prefix(programId) + "sold";
    }

    public static String finalState(long programId) {
        return prefix(programId) + "reservation:final";
    }

    public static String expiration(long programId) {
        return prefix(programId) + "reservation:expiration";
    }

    /** 单请求短期幂等回执；独立 String Key 可设置 TTL，不参与 MySQL Intent。 */
    public static String receipt(long programId, String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId is required");
        }
        return prefix(programId) + "reservation:receipt:" + reservationId;
    }

    public static String ready(long programId) {
        return prefix(programId) + "ready";
    }

    public static String maintenance(long programId) {
        return prefix(programId) + "maintenance";
    }

    public static String version(long programId) {
        return prefix(programId) + "version";
    }

    public static String eventStream(int shard) {
        return shardPrefix(shard) + "stream";
    }

    public static String eventDeadStream(int shard) {
        return shardPrefix(shard) + "dead-stream";
    }
}
