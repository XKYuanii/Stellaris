package com.stellaris.pro.bulkhead;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机并发隔离器。调用方只能在成功获得 {@link Permit} 后释放许可。
 */
public class ConcurrencyBulkhead {

    private final Semaphore semaphore;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter rejectedCounter;
    private final Timer executionTimer;

    public ConcurrencyBulkhead(int maxConcurrentRequests, MeterRegistry meterRegistry) {
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        this.semaphore = new Semaphore(maxConcurrentRequests);
        meterRegistry.gauge("stellaris.gateway.bulkhead.in_flight", inFlight);
        this.rejectedCounter = Counter.builder("stellaris.gateway.bulkhead.rejected").register(meterRegistry);
        this.executionTimer = Timer.builder("stellaris.gateway.bulkhead.execution").register(meterRegistry);
    }

    /**
     * 不等待许可，避免在 Netty 事件循环上阻塞。
     *
     * @return 成功时返回必须关闭的许可；失败时返回 {@code null}
     */
    public Permit tryAcquire() {
        if (!semaphore.tryAcquire()) {
            rejectedCounter.increment();
            return null;
        }
        inFlight.incrementAndGet();
        return new Permit(System.nanoTime());
    }

    public int inFlight() {
        return inFlight.get();
    }

    public final class Permit implements AutoCloseable {
        private final long startNanos;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(long startNanos) {
            this.startNanos = startNanos;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
                semaphore.release();
                executionTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            }
        }
    }
}
