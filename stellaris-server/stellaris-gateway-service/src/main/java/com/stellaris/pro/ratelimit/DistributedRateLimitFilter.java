package com.stellaris.pro.ratelimit;

import com.stellaris.service.ApiRestrictService;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;

import static com.stellaris.constant.GatewayConstant.PROGRAM_ID_HEADER;

/** 在请求验签、用户识别后按配置规则执行分布式 QPS 限流。 */
@Component
public class DistributedRateLimitFilter implements GlobalFilter, Ordered {
    private final RateLimitProperties properties;
    private final RedisTokenBucketRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public DistributedRateLimitFilter(RateLimitProperties properties, RedisTokenBucketRateLimiter rateLimiter,
                                      MeterRegistry meterRegistry) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        List<RateLimitRule> matched = properties.getRules().stream()
                .filter(RateLimitRule::isEnabled).filter(rule -> pathMatcher.match(rule.getPath(), path)).toList();
        return applyRule(exchange, chain, matched, 0);
    }

    private Mono<Void> applyRule(ServerWebExchange exchange, GatewayFilterChain chain,
                                 List<RateLimitRule> rules, int index) {
        if (index >= rules.size()) return chain.filter(exchange);
        RateLimitRule rule = rules.get(index);
        // 当前 RedisCache 是阻塞客户端，必须离开 Netty event-loop；后续可无缝替换为 ReactiveRedisTemplate。
        return Mono.fromCallable(() -> rateLimiter.check(rule,
                        dimensionValue(rule.getDimension(), exchange.getRequest())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> result.allowed() ? applyRule(exchange, chain, rules, index + 1)
                        : reject(exchange, result));
    }

    private Mono<Void> reject(ServerWebExchange exchange, RateLimitResult result) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (result.retryAfterMillis() + 999) / 1000)));
        headers.set("X-RateLimit-Rule", result.ruleId());
        headers.set("X-RateLimit-Dimension", result.rejectDimension().name());
        headers.set("X-RateLimit-Remaining", Long.toString(result.remainingTokens()));
        if (result.degraded()) {
            headers.set("X-RateLimit-Degraded", "true");
        }
        boolean dependencyFailure = result.rejectionCause() == RateLimitRejectionCause.DEPENDENCY_UNAVAILABLE;
        String rejectionReason = dependencyFailure ? "dependency_unavailable"
                : result.degraded() ? "local_fallback_quota" : "quota_exceeded";
        meterRegistry.counter("stellaris_rate_limit_rejections_total", "reason",
                rejectionReason, "rule", result.ruleId()).increment();
        exchange.getResponse().setStatusCode(dependencyFailure
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }

    private String dimensionValue(RateLimitDimension dimension, ServerHttpRequest request) {
        return switch (dimension) {
            case GLOBAL -> "all";
            case IP -> ApiRestrictService.getIpAddress(request);
            case CHANNEL -> valueOrAnonymous(request.getHeaders().getFirst("code"));
            case USER -> valueOrAnonymous(request.getHeaders().getFirst("userId"));
            case PROGRAM -> valueOrAnonymous(request.getHeaders().getFirst(PROGRAM_ID_HEADER));
        };
    }

    private String valueOrAnonymous(String value) {
        return value == null || value.isBlank() ? "anonymous" : value;
    }

    @Override
    public int getOrder() {
        // RequestValidationFilter=-2 先生成可信维度；本过滤器后于验签、先于并发舱壁。
        return -1;
    }
}
