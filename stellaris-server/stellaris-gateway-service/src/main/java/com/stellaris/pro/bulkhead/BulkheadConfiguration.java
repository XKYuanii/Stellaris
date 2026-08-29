package com.stellaris.pro.bulkhead;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BulkheadConfiguration {

    @Bean
    public BulkheadProperties bulkheadProperties() {
        return new BulkheadProperties();
    }

    @Bean
    public ConcurrencyBulkhead concurrencyBulkhead(BulkheadProperties properties, MeterRegistry meterRegistry) {
        return new ConcurrencyBulkhead(properties.getMaxConcurrentRequests(), meterRegistry);
    }
}
