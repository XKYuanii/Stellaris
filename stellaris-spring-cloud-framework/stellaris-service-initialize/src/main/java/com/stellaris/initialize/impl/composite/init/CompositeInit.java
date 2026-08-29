package com.stellaris.initialize.impl.composite.init;

import com.stellaris.initialize.base.AbstractApplicationStartEventListenerHandler;
import com.stellaris.initialize.impl.composite.CompositeContainer;
import lombok.AllArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 组合模式初始化操作执行
 * @author: 阿星不是程序员
 **/
@AllArgsConstructor
public class CompositeInit extends AbstractApplicationStartEventListenerHandler {
    
    private final CompositeContainer compositeContainer;
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
    
    @Override
    public void executeInit(ConfigurableApplicationContext context) {
        compositeContainer.init(context);
    }
}
