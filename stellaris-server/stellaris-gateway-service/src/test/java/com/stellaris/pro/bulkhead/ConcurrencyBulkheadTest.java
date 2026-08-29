package com.stellaris.pro.bulkhead;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyBulkheadTest {

    @Test
    void onlyAcquiredPermitCanBeReleasedAndReleaseIsIdempotent() {
        ConcurrencyBulkhead bulkhead = new ConcurrencyBulkhead(1, new SimpleMeterRegistry());

        ConcurrencyBulkhead.Permit first = bulkhead.tryAcquire();
        assertThat(first).isNotNull();
        assertThat(bulkhead.tryAcquire()).isNull();
        assertThat(bulkhead.inFlight()).isEqualTo(1);

        first.close();
        first.close();

        assertThat(bulkhead.inFlight()).isZero();
        assertThat(bulkhead.tryAcquire()).isNotNull();
    }
}
