package com.stellaris.context;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 上下文获取抽象
 * @author: 阿星不是程序员
 **/
public interface ContextHandler {
    
    /***
     * 从request请求头获取值
     * @param name 值的名
     * @return 具体值
     * 
     */
    String getValueFromHeader(String name);
}