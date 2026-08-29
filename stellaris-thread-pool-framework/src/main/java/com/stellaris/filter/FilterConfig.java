package com.stellaris.filter;

import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 过滤器配置
 * @author: 阿星不是程序员
 **/
public class FilterConfig {

    @Bean
    public OncePerRequestFilter requestParamContextFilter(){
        return new RequestParamContextFilter();
    }
}
