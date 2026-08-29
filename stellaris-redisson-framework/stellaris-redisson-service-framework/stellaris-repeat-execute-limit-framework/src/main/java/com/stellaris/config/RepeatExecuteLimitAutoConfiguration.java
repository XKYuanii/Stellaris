package com.stellaris.config;

import com.stellaris.constant.LockInfoType;
import com.stellaris.handle.RedissonDataHandle;
import com.stellaris.locallock.LocalLockCache;
import com.stellaris.lockinfo.LockInfoHandle;
import com.stellaris.lockinfo.factory.LockInfoHandleFactory;
import com.stellaris.lockinfo.impl.RepeatExecuteLimitLockInfoHandle;
import com.stellaris.repeatexecutelimit.aspect.RepeatExecuteLimitAspect;
import com.stellaris.servicelock.factory.ServiceLockFactory;
import org.springframework.context.annotation.Bean;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 防重复幂等配置
 * @author: 阿星不是程序员
 **/
public class RepeatExecuteLimitAutoConfiguration {
    
    @Bean(LockInfoType.REPEAT_EXECUTE_LIMIT)
    public LockInfoHandle repeatExecuteLimitHandle(){
        return new RepeatExecuteLimitLockInfoHandle();
    }
    
    @Bean
    public RepeatExecuteLimitAspect repeatExecuteLimitAspect(LocalLockCache localLockCache,
                                                             LockInfoHandleFactory lockInfoHandleFactory,
                                                             ServiceLockFactory serviceLockFactory,
                                                             RedissonDataHandle redissonDataHandle){
        return new RepeatExecuteLimitAspect(localLockCache, lockInfoHandleFactory,serviceLockFactory,redissonDataHandle);
    }
}
    