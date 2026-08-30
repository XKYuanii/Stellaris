package com.stellaris.scheduletask;

import com.stellaris.BusinessThreadPool;
import com.stellaris.service.MessageRecordService;
import com.stellaris.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 删除消息记录定时任务
 * @author: xz_y
 **/
@Slf4j
@Component
public class PresentationMessageRecordTask {
    
    @Autowired
    private MessageRecordService messageRecordService;
    
    @Scheduled(cron = "0 0 23 * * ?")
    public void executeTask(){
        BusinessThreadPool.execute( () -> {
            //删除所有的消息记录数据
            log.info("开始删除所有消息记录数据");
            messageRecordService.deleteMessageRecord(DateUtils.now());
        });
    }
}
