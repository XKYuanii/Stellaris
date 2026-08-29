package com.stellaris.toolkit;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.TimeUnit;

/** Redis 租约保护的 Snowflake 节点身份。 */
@Getter
@Setter
public class WorkDataCenterId {

    private Long workId;
    
    private Long dataCenterId;

    /** 使用单调时钟，避免系统时间校准让本地租约判断倒退。 */
    private volatile long leaseValidUntilNanos = Long.MAX_VALUE;

    void refreshLease(long leaseMillis) {
        leaseValidUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leaseMillis);
    }

    void invalidateLease() {
        leaseValidUntilNanos = Long.MIN_VALUE;
    }

    void assertLeaseValid() {
        if (System.nanoTime() >= leaseValidUntilNanos) {
            throw new IllegalStateException("Snowflake node lease expired; refusing to generate a possibly duplicate id");
        }
    }
}
