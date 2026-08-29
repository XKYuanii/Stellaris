package com.stellaris.servicelock.factory;

import com.stellaris.core.ManageLocker;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.ServiceLocker;
import lombok.AllArgsConstructor;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁类型工厂
 * @author: 阿星不是程序员
 **/
@AllArgsConstructor
public class ServiceLockFactory {
    
    private final ManageLocker manageLocker;
    

    public ServiceLocker getLock(LockType lockType){
        ServiceLocker lock;
        switch (lockType) {
            case Fair:
                lock = manageLocker.getFairLocker();
                break;
            case Write:
                lock = manageLocker.getWriteLocker();
                break;
            case Read:
                lock = manageLocker.getReadLocker();
                break;
            default:
                lock = manageLocker.getReentrantLocker();
                break;
        }
        return lock;
    }
}
