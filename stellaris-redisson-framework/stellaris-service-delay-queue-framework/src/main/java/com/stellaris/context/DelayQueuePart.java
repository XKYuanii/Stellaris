package com.stellaris.context;

import com.stellaris.core.ConsumerTask;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息主题
 * @author: 阿星不是程序员
 **/
@Data
public class DelayQueuePart {
    
    private final DelayQueueBasePart delayQueueBasePart;
 
    private final ConsumerTask consumerTask;
    
    public DelayQueuePart(DelayQueueBasePart delayQueueBasePart, ConsumerTask consumerTask){
        this.delayQueueBasePart = delayQueueBasePart;
        this.consumerTask = consumerTask;
    }
}
