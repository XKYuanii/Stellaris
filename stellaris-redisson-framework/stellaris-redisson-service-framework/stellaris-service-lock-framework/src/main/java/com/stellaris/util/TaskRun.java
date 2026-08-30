package com.stellaris.util;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 方法类型执行 无返回值的业务
 * @author: xz_y
 **/
@FunctionalInterface
public interface TaskRun {
    
    /**
     * 执行任务
     * */
    void run();
}
