package com.stellaris.service.trade;

import com.stellaris.entity.Order;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.service.OrderService;
import com.stellaris.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Date;

/** MySQL 截止时间是未支付关单依据；状态 CAS 使重复扫描安全。 */
@Slf4j
@Component
public class OrderExpiryCloseTask {
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final MeterRegistry meterRegistry;

    @Value("${order.expiry.batch-size:100}")
    private int batchSize;

    @Value("${order.expiry.partition-count:1}")
    private int partitionCount;

    @Value("${order.expiry.partition-index:0}")
    private int partitionIndex;

    @Value("${order.expiry.max-batches-per-run:20}")
    private int maxBatchesPerRun;

    @Value("${order.expiry.retry-backoff-ms:60000}")
    private long retryBackoffMs;

    public OrderExpiryCloseTask(OrderMapper orderMapper, OrderService orderService,
                                MeterRegistry meterRegistry) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${order.expiry.fixed-delay-ms:5000}")
    public void closeExpiredOrders() {
        validatePartition();
        int limit = Math.max(1, Math.min(batchSize, 500));
        for (int batch = 0; batch < Math.max(1, maxBatchesPerRun); batch++) {
            Date now = DateUtils.now();
            List<Order> expired = orderMapper.selectExpiredForClose(now, OrderStatus.NO_PAY.getCode(),
                    partitionCount, partitionIndex, limit);
            for (Order order : expired) {
                try {
                    orderService.updateOrderRelatedData(order.getOrderNumber(), OrderStatus.CANCEL);
                    meterRegistry.counter("stellaris_order_expiry_close_total", "result", "success").increment();
                } catch (RuntimeException ex) {
                    defer(order, ex);
                }
            }
            if (expired.size() < limit) return;
        }
    }

    private void defer(Order order, RuntimeException failure) {
        Date now = DateUtils.now();
        Date nextRetry = new Date(now.getTime() + Math.max(1_000, retryBackoffMs));
        try {
            int updated = orderMapper.deferExpiryClose(order.getId(), now, nextRetry,
                    abbreviate(failure.getMessage()));
            meterRegistry.counter("stellaris_order_expiry_close_total",
                    "result", updated == 1 ? "deferred" : "defer_cas_miss").increment();
            log.warn("过期订单关单失败，已推迟重试 orderNumber:{}", order.getOrderNumber(), failure);
        } catch (RuntimeException deferFailure) {
            meterRegistry.counter("stellaris_order_expiry_close_total", "result", "defer_failed").increment();
            log.error("过期订单关单失败且无法记录下次重试时间 orderNumber:{}",
                    order.getOrderNumber(), deferFailure);
        }
    }

    private String abbreviate(String message) {
        if (message == null) return "unknown";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private void validatePartition() {
        if (partitionCount < 1 || partitionIndex < 0 || partitionIndex >= partitionCount) {
            throw new IllegalStateException("order expiry partition must satisfy count >= 1 and 0 <= index < count");
        }
    }
}
