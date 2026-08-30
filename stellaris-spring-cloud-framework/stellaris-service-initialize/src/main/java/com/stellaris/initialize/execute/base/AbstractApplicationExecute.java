package com.stellaris.initialize.execute.base;

import com.stellaris.initialize.base.InitializeHandler;
import lombok.AllArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Comparator;
import java.util.Map;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于处理应用程序启动执行的基类
 * @author: xz_y
 **/
@AllArgsConstructor
public abstract class AbstractApplicationExecute {
    
    private final ConfigurableApplicationContext applicationContext;
    
    public void execute(){
        Map<String, InitializeHandler> initializeHandlerMap = applicationContext.getBeansOfType(InitializeHandler.class);
        initializeHandlerMap.values()
                .stream()
                .filter(initializeHandler -> initializeHandler.type().equals(type()))
                .sorted(Comparator.comparingInt(InitializeHandler::executeOrder))
                .forEach(initializeHandler -> {
                    initializeHandler.executeInit(applicationContext);
                });
    }
    /**
     * 初始化执行 类型
     * @return 类型
     * */
    public abstract String type();
}
