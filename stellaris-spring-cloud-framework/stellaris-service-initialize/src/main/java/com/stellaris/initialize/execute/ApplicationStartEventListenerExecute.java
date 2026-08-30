package com.stellaris.initialize.execute;

import com.stellaris.initialize.execute.base.AbstractApplicationExecute;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import static com.stellaris.initialize.constant.InitializeHandlerType.APPLICATION_EVENT_LISTENER;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于处理 {@link ApplicationStartedEvent} 应用程序启动事件。
 * @author: xz_y
 **/
public class ApplicationStartEventListenerExecute extends AbstractApplicationExecute implements ApplicationListener<ApplicationStartedEvent> {
    
    public ApplicationStartEventListenerExecute(ConfigurableApplicationContext applicationContext){
        super(applicationContext);
    }
    
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        execute();
    }
    
    @Override
    public String type() {
        return APPLICATION_EVENT_LISTENER;
    }
}
