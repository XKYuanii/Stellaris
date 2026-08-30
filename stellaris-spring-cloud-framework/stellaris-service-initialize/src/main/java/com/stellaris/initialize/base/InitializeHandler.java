package com.stellaris.initialize.base;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 初始化执行 顶级抽象 接口
 * @author: xz_y
 **/
public interface InitializeHandler {
    /**
     * 初始化执行 类型
     * @return 类型
     * */
    String type();
    
    /**
     * 执行顺序
     * @return 顺序
     * */
    Integer executeOrder();
    
    /**
     * 执行逻辑
     * @param context 容器上下文
     * */
    void executeInit(ConfigurableApplicationContext context);
    
}
