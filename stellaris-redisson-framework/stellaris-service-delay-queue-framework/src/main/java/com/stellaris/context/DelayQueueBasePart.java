package com.stellaris.context;

import com.stellaris.config.DelayQueueProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.redisson.api.RedissonClient;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟队列配置信息
 * @author: 阿星不是程序员
 **/
@Data
@AllArgsConstructor
public class DelayQueueBasePart {
    
    private final RedissonClient redissonClient;
    
    private final DelayQueueProperties delayQueueProperties;
}
