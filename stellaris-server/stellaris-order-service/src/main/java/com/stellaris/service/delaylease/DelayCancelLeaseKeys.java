package com.stellaris.service.delaylease;
final class DelayCancelLeaseKeys {
    /** 同一 Hash Tag，保证 Redis Cluster 可以执行 pending/processing/payload 多 Key Lua。 */
    static final String PENDING = "stellaris:{delay:cancel}:pending";
    static final String PROCESSING = "stellaris:{delay:cancel}:processing";
    static final String PAYLOAD = "stellaris:{delay:cancel}:payload";
    static final String ATTEMPTS = "stellaris:{delay:cancel}:attempts";
    static final String DEAD = "stellaris:{delay:cancel}:dead";
    private DelayCancelLeaseKeys() {}
}
