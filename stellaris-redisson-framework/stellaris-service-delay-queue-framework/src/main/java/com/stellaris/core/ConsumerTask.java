package com.stellaris.core;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟队列 消费者接口
 * @author: xz_y
 **/
public interface ConsumerTask {
    
    /**
     * 消费任务
     * @param content 具体参数
     * */
    void execute(String content);
    /**
     * 主题
     * @return 主题
     * */
    String topic();
}
