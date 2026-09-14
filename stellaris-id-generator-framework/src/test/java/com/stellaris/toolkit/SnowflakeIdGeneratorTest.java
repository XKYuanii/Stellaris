package com.stellaris.toolkit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void snowflakeIdMustRemainUniqueWhenSequenceRollsToNextMillisecond() {
        long initialTime = 1_800_000_000_000L;
        ControllableClockGenerator generator = new ControllableClockGenerator(initialTime);
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 5_000; i++) {
            assertTrue(ids.add(generator.nextId()), "雪花 ID 不得碰撞");
        }

        assertEquals(initialTime, SnowflakeIdGenerator.parseIdTimestamp(ids.stream()
                .min(Long::compareTo)
                .orElseThrow()));
        assertTrue(ids.stream()
                .mapToLong(SnowflakeIdGenerator::parseIdTimestamp)
                .max()
                .orElseThrow() > initialTime, "序列耗尽后必须推进到下一毫秒");
    }

    @Test
    void leasedNodeMustStopGeneratingAfterOwnershipIsFenced() {
        WorkDataCenterId node = new WorkDataCenterId();
        node.setWorkId(2L);
        node.setDataCenterId(3L);
        node.refreshLease(60_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(node);

        generator.nextId();
        node.invalidateLease();

        assertThrows(IllegalStateException.class, generator::nextId);
    }

    private static final class ControllableClockGenerator extends SnowflakeIdGenerator {

        private final AtomicLong clock;

        private ControllableClockGenerator(long initialTime) {
            super(1L, 1L);
            this.clock = new AtomicLong(initialTime);
        }

        @Override
        protected long timeGen() {
            return clock.get();
        }

        @Override
        protected long tilNextMillis(long lastTimestamp) {
            return clock.updateAndGet(current -> Math.max(current + 1, lastTimestamp + 1));
        }
    }
}
