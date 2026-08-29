package com.stellaris;

import org.springframework.data.redis.connection.stream.ObjectRecord;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis-stream消息处理
 * @author: 阿星不是程序员
 **/
@FunctionalInterface
public interface MessageConsumer {
    
    /**
     * 消息处理
     * @param message 消息
     * 
     * */
    void accept(ObjectRecord<String, String> message);
}