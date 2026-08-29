package com.stellaris.enums;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: job运行类型
 * @author: 阿星不是程序员
 **/
public enum JobRunType {
    /**
     * 同步执行
     * */
    SYNC_RUN,
    
    /**
     * 异步执行
     * */
    ASYNC_RUN;
    
    JobRunType() {
       
    }
    
}
