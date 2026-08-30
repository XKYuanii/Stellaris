package com.stellaris.servicelock.info;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 处理失败抽象
 * @author: xz_y
 **/
public interface LockTimeOutHandler {
    
    /**
     * 处理
     * @param lockName 锁名
     * */
    void handler(String lockName);
}
