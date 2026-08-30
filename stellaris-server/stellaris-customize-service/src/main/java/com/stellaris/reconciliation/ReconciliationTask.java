package com.stellaris.reconciliation;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 对账任务接口
 * @author: xz_y
 **/
@FunctionalInterface
public interface ReconciliationTask {
    
    /***
     * 执行任务
     */
    void run();
}
