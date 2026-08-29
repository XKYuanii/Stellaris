package com.stellaris.feign;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import static com.stellaris.constant.Constant.SERVER_GRAY;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: feign扩展插件配置类
 * @author: 阿星不是程序员
 **/

public class ExtraFeignAutoConfiguration {
    
    @Value(SERVER_GRAY)
    public String serverGray;
    
    @Bean
    public FeignRequestInterceptor feignRequestInterceptor(){
        return new FeignRequestInterceptor(serverGray);
    }
}
