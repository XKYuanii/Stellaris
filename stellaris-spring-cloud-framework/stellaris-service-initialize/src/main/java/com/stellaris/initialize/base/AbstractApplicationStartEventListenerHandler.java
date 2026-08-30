package com.stellaris.initialize.base;

import org.springframework.beans.factory.InitializingBean;

import static com.stellaris.initialize.constant.InitializeHandlerType.APPLICATION_EVENT_LISTENER;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于处理 {@link InitializingBean} 类型 初始化执行 抽象
 * @author: xz_y
 **/
public abstract class AbstractApplicationStartEventListenerHandler implements InitializeHandler {
    
    @Override
    public String type() {
        return APPLICATION_EVENT_LISTENER;
    }
}
