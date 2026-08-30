package com.stellaris.initialize.base;

import jakarta.annotation.PostConstruct;

import static com.stellaris.initialize.constant.InitializeHandlerType.APPLICATION_POST_CONSTRUCT;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于处理 {@link PostConstruct} 类型 初始化执行 抽象
 * @author: xz_y
 **/
public abstract class AbstractApplicationPostConstructHandler implements InitializeHandler {
    
    @Override
    public String type() {
        return APPLICATION_POST_CONSTRUCT;
    }
}
