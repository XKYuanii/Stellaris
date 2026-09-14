package com.stellaris.service.strategy.impl;

import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.ProgramDataPreheatDto;
import com.stellaris.service.composite.impl.ProgramDetailCheckHandler;
import com.stellaris.service.composite.impl.ProgramOrderCreateParamCheckHandler;
import com.stellaris.service.composite.impl.ProgramUserExistCheckHandler;
import com.stellaris.service.reference.ReferenceOrderOrchestrator;
import com.stellaris.service.reference.ReferenceInventoryNotReadyException;
import com.stellaris.service.reference.ReferenceSeatInventoryService;
import com.stellaris.service.ProgramService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** 最终参考链路不使用 JVM/Redisson 座位锁，冲突与事件生成由单次 O(k) Lua 原子裁决。 */
@Slf4j
@Component
public class ProgramOrderV5Strategy {
    private final ReferenceOrderOrchestrator orchestrator;
    private final ProgramService programService;
    private final ProgramOrderCreateParamCheckHandler paramCheckHandler;
    private final ProgramDetailCheckHandler detailCheckHandler;
    private final ProgramUserExistCheckHandler userCheckHandler;
    private final Executor inventoryPreheatExecutor;
    private final Set<Long> preheatingPrograms = ConcurrentHashMap.newKeySet();

    public ProgramOrderV5Strategy(ReferenceOrderOrchestrator orchestrator,
                                  ProgramService programService,
                                  ProgramOrderCreateParamCheckHandler paramCheckHandler,
                                  ProgramDetailCheckHandler detailCheckHandler,
                                  ProgramUserExistCheckHandler userCheckHandler,
                                  @Qualifier("inventoryPreheatExecutor") Executor inventoryPreheatExecutor) {
        this.orchestrator = orchestrator;
        this.programService = programService;
        this.paramCheckHandler = paramCheckHandler;
        this.detailCheckHandler = detailCheckHandler;
        this.userCheckHandler = userCheckHandler;
        this.inventoryPreheatExecutor = inventoryPreheatExecutor;
    }

    public String createOrder(ProgramOrderCreateDto request) {
        paramCheckHandler.validate(request);
        detailCheckHandler.validate(request);
        try {
            return createAfterProgramValidation(request);
        } catch (ReferenceInventoryNotReadyException coldInventory) {
            // 下单线程不执行全量 MySQL -> Redis 重建；同一实例对同一节目只投递一个后台任务。
            triggerPreheat(request.getProgramId());
            throw coldInventory;
        }
    }

    private void triggerPreheat(Long programId) {
        if (programId == null || !preheatingPrograms.add(programId)) return;
        try {
            inventoryPreheatExecutor.execute(() -> {
                try {
                    ProgramDataPreheatDto preheat = new ProgramDataPreheatDto();
                    preheat.setProgramId(programId);
                    programService.dataPreheat(preheat);
                } catch (RuntimeException failure) {
                    log.warn("后台库存预热失败 programId:{}", programId, failure);
                } finally {
                    preheatingPrograms.remove(programId);
                }
            });
        } catch (RuntimeException rejected) {
            preheatingPrograms.remove(programId);
            log.warn("库存预热任务提交失败 programId:{}", programId, rejected);
        }
    }

    private String createAfterProgramValidation(ProgramOrderCreateDto request) {
        if (request.getSeatDtoList() != null && !request.getSeatDtoList().isEmpty()) {
            userCheckHandler.validate(request);
            return orchestrator.create(request);
        }
        ReferenceSeatInventoryService.CandidateSelection selection = reactor.core.publisher.Mono.zip(
                        userCheckHandler.validateAsync(request),
                        orchestrator.prepareAutomaticSelectionAsync(request))
                .map(tuple -> tuple.getT2())
                .block(Duration.ofSeconds(5));
        if (selection == null) {
            throw new IllegalStateException("automatic seat admission returned no selection");
        }
        return orchestrator.create(request, selection);
    }

}
