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
    void orderNumberMustRemainUniqueWhenSequenceRollsToNextMillisecond() {
        long initialTime = 1_800_000_000_000L;
        ControllableClockGenerator generator = new ControllableClockGenerator(initialTime);
        Set<Long> orderNumbers = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            long userId = 10_000L + i;
            long orderNumber = generator.getOrderNumber(userId);
            assertTrue(orderNumbers.add(orderNumber), "订单号不得碰撞");
            assertEquals(userId & 63L, orderNumber & 63L, "低 6 位必须保留用户路由基因");
        }

        assertEquals(initialTime, SnowflakeIdGenerator.parseOrderNumberTimestamp(orderNumbers.stream()
                .min(Long::compareTo)
                .orElseThrow()));
        assertTrue(orderNumbers.stream()
                .mapToLong(SnowflakeIdGenerator::parseOrderNumberTimestamp)
                .max()
                .orElseThrow() > initialTime, "序列耗尽后必须推进到下一毫秒");
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedCapacityParametersMustRejectInvalidTopology() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> generator.getOrderNumber(1L, 3L, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> generator.getOrderNumber(1L, 16L, 8L));
    }

    @Test
    void leasedNodeMustStopGeneratingAfterOwnershipIsFenced() {
        WorkDataCenterId node = new WorkDataCenterId();
        node.setWorkId(2L);
        node.setDataCenterId(3L);
        node.refreshLease(60_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(node);

        generator.getOrderNumber(7L);
        node.invalidateLease();

        assertThrows(IllegalStateException.class, () -> generator.getOrderNumber(7L));
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
