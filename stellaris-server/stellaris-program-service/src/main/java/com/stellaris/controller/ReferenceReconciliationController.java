package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.service.reference.ReferenceReconciliationExecutor;
import com.stellaris.service.reference.ReferenceReconciliationReport;
import com.stellaris.service.kafka.RedisOrderCreateDeadLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/** v5/reference 对账的受控执行入口；生产部署应仅暴露给运维管理面。 */
@RestController
@RequestMapping("/program/reference/reconciliation")
@Tag(name = "reference-reconciliation", description = "v5 三层对账")
@ConditionalOnProperty(prefix = "stellaris.management.operations", name = "enabled", havingValue = "true")
public class ReferenceReconciliationController {
    private final ReferenceReconciliationExecutor executor;
    private final RedisOrderCreateDeadLetterService deadLetterService;

    public ReferenceReconciliationController(ReferenceReconciliationExecutor executor,
                                             RedisOrderCreateDeadLetterService deadLetterService) {
        this.executor = executor;
        this.deadLetterService = deadLetterService;
    }

    @Operation(summary = "执行 v5 事件、订单、库存三层对账")
    @PostMapping("/run")
    public ApiResponse<ReferenceReconciliationReport> run() {
        return ApiResponse.ok(executor.execute());
    }

    @Operation(summary = "按 recordId 重放 Redis Stream 创建订单死信")
    @PostMapping("/stream/dead/replay")
    public ApiResponse<Boolean> replay(@RequestParam int shard, @RequestParam String recordId) {
        return ApiResponse.ok(deadLetterService.replayDead(shard, recordId));
    }
}
