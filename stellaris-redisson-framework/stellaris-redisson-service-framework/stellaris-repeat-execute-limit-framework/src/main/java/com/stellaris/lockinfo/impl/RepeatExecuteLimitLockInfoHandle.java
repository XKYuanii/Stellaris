package com.stellaris.lockinfo.impl;

import com.stellaris.lockinfo.AbstractLockInfoHandle;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 锁信息实现(防重复幂等)
 * @author: 阿星不是程序员
 **/
public class RepeatExecuteLimitLockInfoHandle extends AbstractLockInfoHandle {

    public static final String PREFIX_NAME = "REPEAT_EXECUTE_LIMIT";
    
    @Override
    protected String getLockPrefixName() {
        return PREFIX_NAME;
    }
}
