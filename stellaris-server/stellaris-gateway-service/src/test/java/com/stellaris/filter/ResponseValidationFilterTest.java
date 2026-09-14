package com.stellaris.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseValidationFilterTest {

    @Test
    void passesThroughWithoutDecoratingWhenEncryptionIsNotRequested() {
        ResponseValidationFilter filter = new ResponseValidationFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = forwardedExchange -> {
            forwarded.set(forwardedExchange);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isSameAs(exchange);
        assertThat(forwarded.get().getResponse()).isSameAs(exchange.getResponse());
    }

    @Test
    void noVerifyHeaderOverridesEncryptionHeader() {
        ResponseValidationFilter filter = new ResponseValidationFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("encrypt", "v2")
                        .header("no_verify", "true")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, forwardedExchange -> {
            forwarded.set(forwardedExchange);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isSameAs(exchange);
    }

    @Test
    void decoratesResponseWhenEncryptionIsRequested() {
        ResponseValidationFilter filter = new ResponseValidationFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("encrypt", "v2")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, forwardedExchange -> {
            forwarded.set(forwardedExchange);
            return Mono.empty();
        }).block();

        ServerHttpResponse decorated = forwarded.get().getResponse();
        assertThat(forwarded.get()).isNotSameAs(exchange);
        assertThat(decorated).isNotSameAs(exchange.getResponse());
        assertThat(((ServerHttpResponseDecorator) decorated).getDelegate()).isSameAs(exchange.getResponse());
    }
}
