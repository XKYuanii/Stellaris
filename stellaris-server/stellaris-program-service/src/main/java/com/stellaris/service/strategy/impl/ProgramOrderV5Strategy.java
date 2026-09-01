package com.stellaris.service.strategy.impl;

import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.ProgramDataPreheatDto;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.initialize.impl.composite.CompositeContainer;
import com.stellaris.service.reference.ReferenceOrderOrchestrator;
import com.stellaris.service.reference.ReferenceSeatInventoryService;
import com.stellaris.service.ProgramService;
import org.springframework.stereotype.Component;

/** 最终参考链路不使用 JVM/Redisson 座位锁，冲突与事件生成由单次 O(k) Lua 原子裁决。 */
@Component
public class ProgramOrderV5Strategy {
    private final ReferenceOrderOrchestrator orchestrator;
    private final CompositeContainer compositeContainer;
    private final ProgramService programService;
    private final ReferenceSeatInventoryService inventoryService;

    public ProgramOrderV5Strategy(ReferenceOrderOrchestrator orchestrator,
                                  CompositeContainer compositeContainer,
                                  ProgramService programService,
                                  ReferenceSeatInventoryService inventoryService) {
        this.orchestrator = orchestrator;
        this.compositeContainer = compositeContainer;
        this.programService = programService;
        this.inventoryService = inventoryService;
    }

    public String createOrder(ProgramOrderCreateDto request) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(), request);
        // 仅 Redis 快照缺失的冷节目进入一次节目级单飞预热；热节目不会访问 MySQL。
        if (!inventoryService.isReady(request.getProgramId())) {
            ProgramDataPreheatDto preheat = new ProgramDataPreheatDto();
            preheat.setProgramId(request.getProgramId());
            programService.dataPreheat(preheat);
        }
        return orchestrator.create(request);
    }

}
