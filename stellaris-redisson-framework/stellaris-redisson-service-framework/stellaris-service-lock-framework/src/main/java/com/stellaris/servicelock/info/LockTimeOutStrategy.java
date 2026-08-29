package com.stellaris.servicelock.info;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 策略
 * @author: 阿星不是程序员
 **/
public enum LockTimeOutStrategy implements LockTimeOutHandler{
    /**
     * 快速失败
     * */
    FAIL(){
        @Override
        public void handler(String lockName) {
            String msg = String.format("%s请求频繁",lockName);
            throw new RuntimeException(msg);
        }
    }
}
