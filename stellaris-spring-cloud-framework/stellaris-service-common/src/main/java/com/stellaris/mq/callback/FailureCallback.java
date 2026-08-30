package com.stellaris.mq.callback;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于执行失败的情况
 * @author: xz_y
 **/
@FunctionalInterface
public interface FailureCallback {
    
    /**
     * 执行逻辑
     * @param ex 执行失败的异常当做参数传递
     * */
    void onFailure(Throwable ex);

}