package com.stellaris.lockinfo;

import org.aspectj.lang.JoinPoint;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 锁信息抽象
 * @author: xz_y
 **/
public interface LockInfoHandle {
    /**
     * 获取锁信息
     * @param joinPoint 切面
     * @param name 锁业务名
     * @param keys 锁
     * @return 锁信息
     * */
    String getLockName(JoinPoint joinPoint, String name, String[] keys);
    
    /**
     * 拼装锁信息
     * @param name 锁业务名
     * @param keys 锁
     * @return 锁信息
     * */
    String simpleGetLockName(String name,String[] keys);
}
