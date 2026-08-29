package com.stellaris.scheduletask;

import com.stellaris.BusinessThreadPool;
import com.stellaris.service.MessageRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息记录对账定时任务
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class MessageRecordTask {
    
    @Autowired
    private MessageRecordService messageRecordService;

    @Scheduled(cron = "0 0/1 * * * ? ")
    public void reconciliationTask(){
        BusinessThreadPool.execute( () -> {
            messageRecordService.executeReconciliationTask();
        });
    }
}
