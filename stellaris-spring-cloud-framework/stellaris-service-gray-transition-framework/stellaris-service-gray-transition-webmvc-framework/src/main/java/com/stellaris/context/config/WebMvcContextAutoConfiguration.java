package com.stellaris.context.config;

import com.stellaris.context.ContextHandler;
import com.stellaris.context.impl.WebMvcContextHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: WebMvc配置
 * @author: 阿星不是程序员
 **/
@AutoConfiguration
public class WebMvcContextAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(ContextHandler.class)
    public ContextHandler webMvcContext(){
        return new WebMvcContextHandler();
    }
}
