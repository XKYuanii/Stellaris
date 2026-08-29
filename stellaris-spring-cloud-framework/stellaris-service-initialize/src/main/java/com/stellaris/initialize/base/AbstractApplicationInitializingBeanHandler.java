package com.stellaris.initialize.base;

import org.springframework.beans.factory.InitializingBean;

import static com.stellaris.initialize.constant.InitializeHandlerType.APPLICATION_INITIALIZING_BEAN;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于处理 {@link InitializingBean} 类型 初始化执行 抽象
 * @author: 阿星不是程序员
 **/
public abstract class AbstractApplicationInitializingBeanHandler implements InitializeHandler {
    
    @Override
    public String type() {
        return APPLICATION_INITIALIZING_BEAN;
    }
}
