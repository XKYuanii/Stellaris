package com.stellaris.constant;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 业务类型
 * @author: xz_y
 **/
public class LockInfoType {
    
    /***
     * 防重复执行幂等
     */
    public static final String REPEAT_EXECUTE_LIMIT = "repeat_execute_limit";
    
    /***
     * 分布式锁
     */
    public static final String SERVICE_LOCK = "service_lock";
    
}
