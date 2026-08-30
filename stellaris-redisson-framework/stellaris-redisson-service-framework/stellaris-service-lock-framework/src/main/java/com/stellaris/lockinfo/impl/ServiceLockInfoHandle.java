package com.stellaris.lockinfo.impl;

import com.stellaris.lockinfo.AbstractLockInfoHandle;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 锁信息实现(分布式锁)
 * @author: xz_y
 **/
public class ServiceLockInfoHandle extends AbstractLockInfoHandle {

    private static final String LOCK_PREFIX_NAME = "SERVICE_LOCK";
    
    @Override
    protected String getLockPrefixName() {
        return LOCK_PREFIX_NAME;
    }
}
