package com.stellaris.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps Stream health metrics responsive when business recovery tasks block on dependencies. */
@Configuration
public class OrderStreamObservationSchedulingConfiguration {

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(@Value("${spring.task.scheduling.pool.size:5}") int poolSize) {
        return scheduler(Math.max(5, poolSize), "order-scheduling-");
    }

    @Bean("orderStreamObservationScheduler")
    public ThreadPoolTaskScheduler orderStreamObservationScheduler() {
        return scheduler(1, "order-stream-observation-");
    }

    private ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
