package com.stellaris.pro.bulkhead;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 分层限流通过后才占用下游并发位，避免被本应快速拒绝的洪峰耗尽舱壁。 */
@Component
public class ConcurrencyBulkheadFilter implements GlobalFilter, Ordered {
    private final BulkheadProperties properties;
    private final ConcurrencyBulkhead bulkhead;

    public ConcurrencyBulkheadFilter(BulkheadProperties properties, ConcurrencyBulkhead bulkhead) {
        this.properties = properties;
        this.bulkhead = bulkhead;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        ConcurrencyBulkhead.Permit permit = bulkhead.tryAcquire();
        if (permit == null) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }
        return Mono.defer(() -> chain.filter(exchange)).doFinally(signalType -> permit.close());
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
