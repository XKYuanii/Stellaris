package com.stellaris.locallock;

import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 本地锁缓存
 * @author: xz_y
 **/
public class LocalLockCache {
    
    /**
     * 本地锁缓存
     * */
    private ReentrantLock[] stripes;

    @Value("${local-lock.stripes:4096}")
    private int stripeCount;
    
    @PostConstruct
    public void localLockCacheInit(){
        int size = 1;
        while (size < Math.max(64, stripeCount)) size <<= 1;
        stripes = new ReentrantLock[size];
        for (int index = 0; index < size; index++) {
            // 所有调用共用同一组公平锁，避免相同 key 因 fair 参数不同落到两个锁对象。
            stripes[index] = new ReentrantLock(true);
        }
    }
    
    /**
     * 获得锁，Caffeine的get是线程安全的
     * */
    public ReentrantLock getLock(String lockKey,boolean fair){
        if (lockKey == null) throw new IllegalArgumentException("lockKey is required");
        int hash = lockKey.hashCode();
        hash ^= hash >>> 16;
        return stripes[hash & (stripes.length - 1)];
    }
}
