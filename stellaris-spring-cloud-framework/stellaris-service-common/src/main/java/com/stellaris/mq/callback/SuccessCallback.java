package com.stellaris.mq.callback;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于执行成功的情况
 * @author: xz_y
 **/
@FunctionalInterface
public interface SuccessCallback<T> {
    
    /**
     * 执行逻辑
     * @param result 执行成功的结果当做参数传递
     * */
    void onSuccess(T result);

}
