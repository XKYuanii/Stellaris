package com.stellaris.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/** Keeps service-to-service and manual maintenance endpoints off the public gateway. */
@Component
public class InternalEndpointBlockFilter implements GlobalFilter, Ordered {
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> BLOCKED_PATHS = List.of(
            "/**/order/test",
            "/**/order/get/cache",
            "/**/order/simple/list",
            "/**/order/account/order/count",
            "/**/order/reference/**",
            "/**/order/create/dlt/replay",
            "/**/order/reservation/transition/replay",
            "/**/program/reference/reconciliation/**",
            "/**/user/get/mobile",
            "/**/user/exist",
            "/**/user/get/user/ticket/list"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isBlockedPath(exchange.getRequest().getPath().value())) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    static boolean isBlockedPath(String path) {
        return BLOCKED_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    public int getOrder() {
        return -3;
    }
}
