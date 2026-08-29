package com.stellaris.lock;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 锁的任务
 * @author: 阿星不是程序员
 **/
@FunctionalInterface
public interface LockTask<V> {
    /**
     * 执行锁的任务
     * @return 结果
     */
    V execute();
}