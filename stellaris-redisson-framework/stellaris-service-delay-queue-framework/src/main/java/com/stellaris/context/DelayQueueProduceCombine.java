package com.stellaris.context;

import com.stellaris.core.DelayProduceQueue;
import com.stellaris.core.IsolationRegionSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟队列 发送者 分片选择
 * @author: xz_y
 **/
public class DelayQueueProduceCombine {
    
    private final IsolationRegionSelector isolationRegionSelector;
    
    private final List<DelayProduceQueue> delayProduceQueueList = new ArrayList<>();
    
    public DelayQueueProduceCombine(DelayQueueBasePart delayQueueBasePart,String topic){
        Integer isolationRegionCount = delayQueueBasePart.getDelayQueueProperties().getIsolationRegionCount();
        isolationRegionSelector =new IsolationRegionSelector(isolationRegionCount);
        //开始分片
        for(int i = 0; i < isolationRegionCount; i++) {
            delayProduceQueueList.add(new DelayProduceQueue(delayQueueBasePart.getRedissonClient(),topic + "-" + i));
        }
    }
    
    public void offer(String content,long delayTime, TimeUnit timeUnit){
        int index = isolationRegionSelector.getIndex();
        //拿取分片
        delayProduceQueueList.get(index).offer(content, delayTime, timeUnit);
    }
}
