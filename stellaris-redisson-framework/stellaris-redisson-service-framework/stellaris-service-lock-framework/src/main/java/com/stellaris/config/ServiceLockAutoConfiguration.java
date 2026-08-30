package com.stellaris.config;

import com.stellaris.constant.LockInfoType;
import com.stellaris.core.ManageLocker;
import com.stellaris.lockinfo.LockInfoHandle;
import com.stellaris.lockinfo.factory.LockInfoHandleFactory;
import com.stellaris.lockinfo.impl.ServiceLockInfoHandle;
import com.stellaris.servicelock.aspect.ServiceLockAspect;
import com.stellaris.servicelock.factory.ServiceLockFactory;
import com.stellaris.util.ServiceLockTool;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 配置
 * @author: xz_y
 **/
public class ServiceLockAutoConfiguration {
    
    @Bean(LockInfoType.SERVICE_LOCK)
    public LockInfoHandle serviceLockInfoHandle(){
        return new ServiceLockInfoHandle();
    }
    
    @Bean
    public ManageLocker manageLocker(RedissonClient redissonClient){
        return new ManageLocker(redissonClient);
    }
    
    @Bean
    public ServiceLockFactory serviceLockFactory(ManageLocker manageLocker){
        return new ServiceLockFactory(manageLocker);
    }
    
    @Bean
    public ServiceLockAspect serviceLockAspect(LockInfoHandleFactory lockInfoHandleFactory,ServiceLockFactory serviceLockFactory){
        return new ServiceLockAspect(lockInfoHandleFactory,serviceLockFactory);
    }
    
    @Bean
    public ServiceLockTool serviceLockUtil(LockInfoHandleFactory lockInfoHandleFactory,ServiceLockFactory serviceLockFactory){
        return new ServiceLockTool(lockInfoHandleFactory,serviceLockFactory);
    }
}
