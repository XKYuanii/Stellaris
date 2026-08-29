package com.stellaris.servicelock;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 锁类型
 * @author: 阿星不是程序员
 **/
public enum LockType {
    /**
     * 可重入锁
     */
    Reentrant,
    /**
     * 公平锁
     */
    Fair,
    /**
     * 读锁
     */
    Read,
    /**
     * 写锁
     */
    Write;

    LockType() {
    }

}
