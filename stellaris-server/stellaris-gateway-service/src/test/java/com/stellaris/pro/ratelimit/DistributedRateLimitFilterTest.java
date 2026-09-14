package com.stellaris.pro.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static com.stellaris.constant.GatewayConstant.PROGRAM_ID_HEADER;

class DistributedRateLimitFilterTest {

    @Test
    void evaluatesMatchedRulesInOrderAndInvokesChainOnce() {
        RateLimitRule user = rule("user", RateLimitDimension.USER);
        RateLimitRule program = rule("program", RateLimitDimension.PROGRAM);
        RateLimitRule global = rule("global", RateLimitDimension.GLOBAL);
        RateLimitProperties properties = properties(user, program, global);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        when(limiter.check(user, "42")).thenReturn(Mono.just(allowed(user)));
        when(limiter.check(program, "1001")).thenReturn(Mono.just(allowed(program)));
        when(limiter.check(global, "all")).thenReturn(Mono.just(allowed(global)));

        DistributedRateLimitFilter filter = new DistributedRateLimitFilter(
                properties, limiter, new SimpleMeterRegistry());
        MockServerWebExchange exchange = exchange();
        AtomicInteger chainCalls = new AtomicInteger();
        GatewayFilterChain chain = ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalls).hasValue(1);
        InOrder order = inOrder(limiter);
        order.verify(limiter).check(user, "42");
        order.verify(limiter).check(program, "1001");
        order.verify(limiter).check(global, "all");
    }

    @Test
    void stopsAtFirstRejectionAndDoesNotInvokeChain() {
        RateLimitRule user = rule("user", RateLimitDimension.USER);
        RateLimitRule program = rule("program", RateLimitDimension.PROGRAM);
        RateLimitRule global = rule("global", RateLimitDimension.GLOBAL);
        RateLimitProperties properties = properties(user, program, global);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        when(limiter.check(user, "42")).thenReturn(Mono.just(allowed(user)));
        when(limiter.check(program, "1001"))
                .thenReturn(Mono.just(RateLimitResult.rejected(
                        0, 1200, program.getId(), program.getDimension(), false)));

        DistributedRateLimitFilter filter = new DistributedRateLimitFilter(
                properties, limiter, new SimpleMeterRegistry());
        MockServerWebExchange exchange = exchange();
        AtomicInteger chainCalls = new AtomicInteger();

        filter.filter(exchange, ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(chainCalls).hasValue(0);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Rule")).isEqualTo("program");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("2");
        verify(limiter, never()).check(global, "all");
    }

    @Test
    void skipsLimiterWhenNoRuleMatches() {
        RateLimitRule rule = rule("other", RateLimitDimension.GLOBAL);
        rule.setPath("/other/**");
        RateLimitProperties properties = properties(rule);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        DistributedRateLimitFilter filter = new DistributedRateLimitFilter(
                properties, limiter, new SimpleMeterRegistry());
        AtomicInteger chainCalls = new AtomicInteger();

        filter.filter(exchange(), ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(chainCalls).hasValue(1);
        verifyNoInteractions(limiter);
    }

    private static RateLimitProperties properties(RateLimitRule... rules) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setRules(List.of(rules));
        return properties;
    }

    private static RateLimitRule rule(String id, RateLimitDimension dimension) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId(id);
        rule.setPath("/stellaris/program/**");
        rule.setDimension(dimension);
        rule.setCapacity(1000);
        rule.setRefillTokens(1000);
        rule.setRefillPeriodMillis(1000);
        return rule;
    }

    private static RateLimitResult allowed(RateLimitRule rule) {
        return RateLimitResult.allowed(999, rule.getId(), rule.getDimension(), false);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/stellaris/program/order/create/v5")
                .header("userId", "42")
                .header(PROGRAM_ID_HEADER, "1001")
                .build());
    }
}
