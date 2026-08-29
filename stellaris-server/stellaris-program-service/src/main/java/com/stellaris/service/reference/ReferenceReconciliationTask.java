package com.stellaris.service.reference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.stellaris.servicelock.annotion.ServiceLock;
import static com.stellaris.core.DistributedLockConstants.REFERENCE_RECONCILIATION;

/** 默认关闭，先通过内部管理入口和演练验证后再启用定时运行。 */
@Slf4j
@Component
public class ReferenceReconciliationTask {
    private final ReferenceReconciliationExecutor executor;

    @Value("${reference-reconciliation.enabled:false}")
    private boolean enabled;

    public ReferenceReconciliationTask(ReferenceReconciliationExecutor executor) {
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${reference-reconciliation.fixed-delay-ms:60000}")
    @ServiceLock(name = REFERENCE_RECONCILIATION, keys = {})
    public void run() {
        if (!enabled) {
            return;
        }
        ReferenceReconciliationReport report = executor.execute();
        log.info("v5 Redis Stream 对账完成 reservations:{} streamRecords:{} orders:{} programs:{} findings:{}",
                report.getInspectedIntents(), report.getInspectedEvents(), report.getInspectedOrders(),
                report.getInspectedPrograms(), report.getFindings().size());
    }
}
