package com.stellaris.service.stream;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStreamPendingRecoveryTest {

    @Test
    void calculatesWholeSecondsFromTheRedisRecordTimestamp() {
        assertThat(OrderStreamObservabilityTask.recordAgeSeconds(RecordId.of(10_000, 3), 15_999))
                .isEqualTo(5L);
    }

    @Test
    void clampsClockSkewAndMissingIdsToZero() {
        assertThat(OrderStreamObservabilityTask.recordAgeSeconds(RecordId.of(20_000, 0), 19_000))
                .isZero();
        assertThat(OrderStreamObservabilityTask.recordAgeSeconds(null, 19_000)).isZero();
    }
}
