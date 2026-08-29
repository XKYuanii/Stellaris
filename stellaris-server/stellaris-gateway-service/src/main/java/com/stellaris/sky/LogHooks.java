package com.stellaris.sky;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Operators;


@Component
public class LogHooks {

    private static final String KEY = "logMdc";

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void setHook() {
        reactor.core.publisher.Hooks.onEachOperator(KEY,
                Operators.lift((scannable, coreSubscriber) -> new MdcSubscriber(coreSubscriber)));
    }

    @PreDestroy
    public void resetHook() {
        reactor.core.publisher.Hooks.resetOnEachOperator(KEY);
    }

}