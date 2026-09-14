package com.stellaris.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class PaymentChannelReconciliationConfiguration {

    @Bean("paymentChannelReconciliationExecutor")
    public ThreadPoolTaskExecutor paymentChannelReconciliationExecutor(
            @Value("${payment-channel-reconciliation.executor.core-size:10}") int coreSize,
            @Value("${payment-channel-reconciliation.executor.max-size:10}") int maxSize,
            @Value("${payment-channel-reconciliation.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("payment-channel-reconciliation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
