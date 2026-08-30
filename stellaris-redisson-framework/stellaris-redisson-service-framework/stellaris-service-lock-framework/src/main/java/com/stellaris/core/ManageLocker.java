package com.stellaris.core;

import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.ServiceLocker;
import com.stellaris.servicelock.impl.RedissonFairLocker;
import com.stellaris.servicelock.impl.RedissonReadLocker;
import com.stellaris.servicelock.impl.RedissonReentrantLocker;
import com.stellaris.servicelock.impl.RedissonWriteLocker;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

import static com.stellaris.servicelock.LockType.Fair;
import static com.stellaris.servicelock.LockType.Read;
import static com.stellaris.servicelock.LockType.Reentrant;
import static com.stellaris.servicelock.LockType.Write;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 锁缓存
 * @author: xz_y
 **/
public class ManageLocker {

    private final Map<LockType, ServiceLocker> cacheLocker = new HashMap<>();
    
    public ManageLocker(RedissonClient redissonClient){
        cacheLocker.put(Reentrant,new RedissonReentrantLocker(redissonClient));
        cacheLocker.put(Fair,new RedissonFairLocker(redissonClient));
        cacheLocker.put(Write,new RedissonWriteLocker(redissonClient));
        cacheLocker.put(Read,new RedissonReadLocker(redissonClient));
    }
    
    public ServiceLocker getReentrantLocker(){
        return cacheLocker.get(Reentrant);
    }
    
    public ServiceLocker getFairLocker(){
        return cacheLocker.get(Fair);
    }
    
    public ServiceLocker getWriteLocker(){
        return cacheLocker.get(Write);
    }
    
    public ServiceLocker getReadLocker(){
        return cacheLocker.get(Read);
    }
}
