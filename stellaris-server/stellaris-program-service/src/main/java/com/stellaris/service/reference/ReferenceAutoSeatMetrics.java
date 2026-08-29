package com.stellaris.service.reference;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 自动配座低基数指标；requestId、节目和用户只进入日志，不进入 tag。 */
@Component
public class ReferenceAutoSeatMetrics {
    private final MeterRegistry meterRegistry;

    public ReferenceAutoSeatMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void request() {
        meterRegistry.counter("stellaris_auto_seat_request_total").increment();
    }

    public void conflict(String code) {
        meterRegistry.counter("stellaris_auto_seat_conflict_total", "code", normalize(code)).increment();
    }

    public void retry(String code) {
        meterRegistry.counter("stellaris_auto_seat_retry_total", "code", normalize(code)).increment();
    }

    public void complete(int retryCount) {
        meterRegistry.counter("stellaris_auto_seat_retry_count_total", "retries",
                String.valueOf(Math.max(0, retryCount))).increment();
    }

    public void finalFailure(String reason, int retryCount) {
        complete(retryCount);
        meterRegistry.counter("stellaris_auto_seat_final_failure_total", "reason", normalize(reason)).increment();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
