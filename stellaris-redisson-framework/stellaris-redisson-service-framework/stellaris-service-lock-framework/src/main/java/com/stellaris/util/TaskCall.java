package com.stellaris.util;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分布式锁 方法类型执行 有返回值的业务
 * @author: xz_y
 **/
@FunctionalInterface
public interface TaskCall<V> {

    /**
     * 执行任务
     * @return 结果
     * */
    V call();
}
