package com.stellaris.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson2.JSON;
import com.stellaris.client.ProgramClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramRecordTaskListDto;
import com.stellaris.dto.ProgramRecordTaskUpdateDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.HandleStatus;
import com.stellaris.service.OrderTaskService;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.util.DateUtils;
import com.stellaris.vo.ProgramRecordTaskVo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 对账定时任务
 * @author: xz_y
 **/
@Slf4j
@Component
public class ReconciliationTask {

    @Autowired
    private OrderTaskService orderTaskService;
    
    @Autowired
    private ProgramClient programClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${order.reconciliation.enabled:true}")
    private boolean enabled;

    @Value("${order.reconciliation.lookback-minutes:3}")
    private int lookbackMinutes;

    @Scheduled(cron = "${order.reconciliation.cron:0 0/3 * * * ?}")
    @ServiceLock(name = "inventory-reconciliation-schedule", keys = {"'all'"}, waitTime = 0)
    public void reconciliationTask(){
        if (!enabled) {
            return;
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        meterRegistry.counter("stellaris_inventory_reconciliation_runs_total").increment();
        try {
            log.info("库存对账任务开始执行");
            ProgramRecordTaskListDto programRecordTaskListDto = new ProgramRecordTaskListDto();
            programRecordTaskListDto.setHandleStatus(HandleStatus.NO_HANDLE.getCode());
            programRecordTaskListDto.setCreateTime(DateUtils.addMinute(DateUtils.now(), -lookbackMinutes));
            ApiResponse<List<ProgramRecordTaskVo>> listApiResponse = programClient.select(programRecordTaskListDto);
            if (!Objects.equals(listApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                meterRegistry.counter("stellaris_inventory_reconciliation_total", "result", "query_failed").increment();
                log.error("获取节目对账记录任务集合失败 dto:{} message:{}",
                        JSON.toJSONString(programRecordTaskListDto), listApiResponse.getMessage());
                return;
            }
            List<ProgramRecordTaskVo> taskList = listApiResponse.getData();
            if (CollectionUtil.isEmpty(taskList)) {
                meterRegistry.counter("stellaris_inventory_reconciliation_total", "result", "empty").increment();
                return;
            }
            meterRegistry.counter("stellaris_inventory_reconciliation_task_records_total").increment(taskList.size());
            Map<Long, List<ProgramRecordTaskVo>> tasksByProgram = taskList.stream()
                    .collect(Collectors.groupingBy(ProgramRecordTaskVo::getProgramId));

            for (Map.Entry<Long, List<ProgramRecordTaskVo>> entry : tasksByProgram.entrySet()) {
                Long programId = entry.getKey();
                try {
                    // 没有对应订单库事实时保留任务，等待可靠事件重投或人工处理，不能误标已完成。
                    if (Objects.isNull(orderTaskService.reconciliationTask(programId))) {
                        meterRegistry.counter("stellaris_inventory_reconciliation_total", "result", "waiting_order").increment();
                        log.warn("库存对账等待订单事实 programId:{} taskCount:{}", programId, entry.getValue().size());
                        continue;
                    }

                    Set<Date> createTimeSet = entry.getValue().stream()
                            .map(ProgramRecordTaskVo::getCreateTime)
                            .collect(Collectors.toSet());
                    ProgramRecordTaskUpdateDto updateDto = new ProgramRecordTaskUpdateDto();
                    updateDto.setBeforeHandleStatus(HandleStatus.NO_HANDLE.getCode());
                    updateDto.setAfterHandleStatus(HandleStatus.YES_HANDLE.getCode());
                    updateDto.setCreateTimeSet(createTimeSet);
                    ApiResponse<Integer> updateApiResponse = programClient.update(updateDto);
                    if (!Objects.equals(updateApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                        throw new IllegalStateException("更新节目对账任务失败: " + updateApiResponse.getMessage());
                    }
                    meterRegistry.counter("stellaris_inventory_reconciliation_total", "result", "success").increment();
                } catch (Exception ex) {
                    meterRegistry.counter("stellaris_inventory_reconciliation_total", "result", "failed").increment();
                    log.error("节目库存对账失败 programId:{}", programId, ex);
                }
            }
        } catch (Exception ex) {
            meterRegistry.counter("stellaris_inventory_reconciliation_total", "result", "scheduler_failed").increment();
            log.error("库存对账调度失败", ex);
        } finally {
            sample.stop(meterRegistry.timer("stellaris_inventory_reconciliation_duration"));
        }
    }
}
