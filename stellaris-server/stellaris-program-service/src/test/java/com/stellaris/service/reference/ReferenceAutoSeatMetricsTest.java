package com.stellaris.service.reference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceAutoSeatMetricsTest {

    @Test
    void recordsOnlyBoundedLowCardinalityDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReferenceAutoSeatMetrics metrics = new ReferenceAutoSeatMetrics(registry);

        metrics.request();
        metrics.conflict("SEAT_UNAVAILABLE");
        metrics.retry("SEAT_UNAVAILABLE");
        metrics.complete(1);
        metrics.finalFailure("NO_CANDIDATE_GROUP", 0);

        assertThat(registry.get("stellaris_auto_seat_request_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("stellaris_auto_seat_conflict_total").tag("code", "SEAT_UNAVAILABLE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("stellaris_auto_seat_retry_total").tag("code", "SEAT_UNAVAILABLE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("stellaris_auto_seat_retry_count_total").tag("retries", "1")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("stellaris_auto_seat_final_failure_total").tag("reason", "NO_CANDIDATE_GROUP")
                .counter().count()).isEqualTo(1);
    }
}
