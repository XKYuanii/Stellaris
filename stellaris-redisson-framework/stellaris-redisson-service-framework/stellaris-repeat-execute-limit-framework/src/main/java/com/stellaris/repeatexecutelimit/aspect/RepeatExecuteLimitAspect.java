package com.stellaris.repeatexecutelimit.aspect;

import com.stellaris.constant.LockInfoType;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.handle.RedissonDataHandle;
import com.stellaris.locallock.LocalLockCache;
import com.stellaris.lockinfo.LockInfoHandle;
import com.stellaris.lockinfo.factory.LockInfoHandleFactory;
import com.stellaris.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.ServiceLocker;
import com.stellaris.servicelock.factory.ServiceLockFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.stellaris.repeatexecutelimit.constant.RepeatExecuteLimitConstant.PREFIX_NAME;
import static com.stellaris.repeatexecutelimit.constant.RepeatExecuteLimitConstant.SUCCESS_FLAG;

/**
 /**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 防重复幂等 切面
 * @author: 阿星不是程序员
 **/
@Slf4j
@Aspect
@Order(-11)
@AllArgsConstructor
public class RepeatExecuteLimitAspect {
    
    private final LocalLockCache localLockCache;
    
    private final LockInfoHandleFactory lockInfoHandleFactory;
    
    private final ServiceLockFactory serviceLockFactory;
    
    private final RedissonDataHandle redissonDataHandle;
    
    
    @Around("@annotation(repeatLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatLimit) throws Throwable {
        long durationTime = repeatLimit.durationTime();
        String message = repeatLimit.message();
        Object obj;
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.REPEAT_EXECUTE_LIMIT);
        String lockName = lockInfoHandle.getLockName(joinPoint,repeatLimit.name(), repeatLimit.keys());
        String repeatFlagName = PREFIX_NAME + lockName;
        String flagObject = redissonDataHandle.get(repeatFlagName);
        if (SUCCESS_FLAG.equals(flagObject)) {
            throw new StellarisFrameException(message);
        }
        ReentrantLock localLock = localLockCache.getLock(lockName,true);
        boolean localLockResult = localLock.tryLock();
        if (!localLockResult) {
            throw new StellarisFrameException(message);
        }
        try {
            ServiceLocker lock = serviceLockFactory.getLock(LockType.Fair);
            boolean result = lock.tryLock(lockName, TimeUnit.SECONDS, 0);
            if (result) {
                Throwable businessFailure = null;
                try{
                    flagObject = redissonDataHandle.get(repeatFlagName);
                    if (SUCCESS_FLAG.equals(flagObject)) {
                        throw new StellarisFrameException(message);
                    }
                    obj = joinPoint.proceed();
                    if (durationTime > 0) {
                        try {
                            redissonDataHandle.set(repeatFlagName,SUCCESS_FLAG,durationTime,TimeUnit.SECONDS);
                        }catch (Exception e) {
                            log.error("getBucket error",e);
                        }
                    }
                    return obj;
                } catch (Throwable throwable) {
                    businessFailure = throwable;
                    throw throwable;
                } finally {
                    try {
                        lock.unlock(lockName);
                    } catch (RuntimeException unlockFailure) {
                        if (businessFailure != null) {
                            businessFailure.addSuppressed(unlockFailure);
                            log.error("repeatExecute distributed unlock failed after business exception lockName:{}",
                                    lockName, unlockFailure);
                        } else {
                            throw unlockFailure;
                        }
                    }
                }
            }else{
                throw new StellarisFrameException(message);
            }
        } finally {
            if (localLock.isHeldByCurrentThread()) localLock.unlock();
        }
    }
}
