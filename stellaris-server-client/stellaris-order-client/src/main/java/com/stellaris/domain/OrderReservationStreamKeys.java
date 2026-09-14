package com.stellaris.domain;

/** 创建订单 Stream 的跨服务稳定命名契约。 */
public final class OrderReservationStreamKeys {
    public static final int SALE_SHARD_COUNT = 16;

    private OrderReservationStreamKeys() {
    }

    public static int shard(long programId) {
        return Math.floorMod(programId, SALE_SHARD_COUNT);
    }

    public static String stream(int shard) {
        validateShard(shard);
        return "stellaris:{sale:" + shard + "}:reservation:event:stream";
    }

    /** Shard-wide index used by Order to discover expired reservations without Redis key scans. */
    public static String expirationIndex(int shard) {
        validateShard(shard);
        return "stellaris:{sale:" + shard + "}:reservation:expiration:index";
    }

    public static String expirationMember(long programId, String intentId) {
        if (programId <= 0 || intentId == null || intentId.isBlank() || intentId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("valid programId and intentId are required");
        }
        return programId + "|" + intentId;
    }

    public static ReservationExpiration parseExpirationMember(String member) {
        if (member == null) throw new IllegalArgumentException("expiration member is required");
        int separator = member.indexOf('|');
        if (separator <= 0 || separator == member.length() - 1) {
            throw new IllegalArgumentException("invalid expiration member: " + member);
        }
        try {
            long programId = Long.parseLong(member.substring(0, separator));
            String intentId = member.substring(separator + 1);
            if (programId <= 0 || intentId.isBlank()) throw new NumberFormatException();
            return new ReservationExpiration(programId, intentId);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid expiration member: " + member, invalid);
        }
    }

    public record ReservationExpiration(long programId, String intentId) {
    }

    private static void validateShard(int shard) {
        if (shard < 0 || shard >= SALE_SHARD_COUNT) {
            throw new IllegalArgumentException("invalid sale shard: " + shard);
        }
    }
}
