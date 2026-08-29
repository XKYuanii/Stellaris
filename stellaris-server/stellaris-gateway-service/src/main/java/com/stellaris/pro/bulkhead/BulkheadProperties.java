package com.stellaris.pro.bulkhead;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;

/**
 * 网关本机并发隔离配置。它保护当前实例的下游并发，不表示 QPS 配额。
 */
@Data
public class BulkheadProperties {

    @Value("${gateway.bulkhead.enabled:false}")
    private boolean enabled;

    @Value("${gateway.bulkhead.max-concurrent-requests:200}")
    private int maxConcurrentRequests;
}
