package com.stellaris.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.dto.RefundDto;
import com.stellaris.entity.RefundIntent;
import com.stellaris.mapper.RefundIntentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Date;

/** FAILED 退款 Intent 的可靠重试；使用原退款号，不能新建渠道退款。 */
@Component
@Slf4j
public class RefundIntentRetryTask {
    private final RefundIntentMapper mapper;
    private final PayService payService;
    @Value("${refund-intent-retry.enabled:false}") private boolean enabled;
    @Value("${refund-intent-retry.batch-size:50}") private int batchSize;
    @Value("${refund-intent-retry.processing-timeout-ms:60000}") private long processingTimeoutMs;
    @Value("${refund-intent-retry.max-attempts:10}") private int maxAttempts;
    public RefundIntentRetryTask(RefundIntentMapper mapper, PayService payService) { this.mapper = mapper; this.payService = payService; }
    @Scheduled(fixedDelayString = "${refund-intent-retry.fixed-delay-ms:60000}")
    public void retryFailed() {
        if (!enabled) return;
        Date stale = new Date(System.currentTimeMillis() - Math.max(1000, processingTimeoutMs));
        List<RefundIntent> intents = mapper.selectList(Wrappers.lambdaQuery(RefundIntent.class)
                .and(query -> query.nested(failed -> failed.eq(RefundIntent::getIntentStatus, "FAILED")
                                .le(RefundIntent::getNextRetryTime, new Date()))
                        .or(nested -> nested.eq(RefundIntent::getIntentStatus, "PROCESSING")
                                .le(RefundIntent::getEditTime, stale)))
                .orderByAsc(RefundIntent::getEditTime).last("LIMIT " + Math.max(1, batchSize)));
        for (RefundIntent intent : intents) try {
            if (java.util.Objects.requireNonNullElse(intent.getRetryCount(), 0) >= maxAttempts) {
                RefundIntent dead = new RefundIntent(); dead.setIntentStatus("DEAD"); dead.setEditTime(new Date());
                mapper.update(dead, Wrappers.lambdaUpdate(RefundIntent.class)
                        .eq(RefundIntent::getId, intent.getId())
                        .in(RefundIntent::getIntentStatus, "FAILED", "PROCESSING"));
                continue;
            }
            if ("PROCESSING".equals(intent.getIntentStatus())) {
                RefundIntent recover = new RefundIntent(); recover.setIntentStatus("FAILED");
                recover.setNextRetryTime(new Date());
                recover.setLastError("processing lease timeout; retry with stable refundNo");
                recover.setEditTime(new Date());
                int changed = mapper.update(recover, Wrappers.lambdaUpdate(RefundIntent.class)
                        .eq(RefundIntent::getId, intent.getId()).eq(RefundIntent::getIntentStatus, "PROCESSING")
                        .le(RefundIntent::getEditTime, stale));
                if (changed != 1) continue;
            }
            RefundDto dto = new RefundDto(); dto.setOrderNumber(intent.getOutOrderNo()); dto.setAmount(intent.getAmount());
            dto.setChannel(intent.getChannel()); dto.setReason(intent.getReason()); payService.refund(dto);
        } catch (Exception ex) { log.warn("refund intent retry failed refundNo:{}", intent.getRefundNo(), ex); }
    }
}
