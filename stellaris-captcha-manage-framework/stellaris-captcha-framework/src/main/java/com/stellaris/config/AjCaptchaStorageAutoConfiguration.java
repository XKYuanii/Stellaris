package com.stellaris.config;

import com.stellaris.properties.AjCaptchaProperties;
import com.stellaris.captcha.service.CaptchaCacheService;
import com.stellaris.captcha.service.impl.CaptchaServiceFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 存储策略自动配置
 * @author: xz_y
 **/
@Configuration
public class AjCaptchaStorageAutoConfiguration {

    @Bean(name = "AjCaptchaCacheService")
    @ConditionalOnMissingBean
    public CaptchaCacheService captchaCacheService(AjCaptchaProperties config){
        //缓存类型redis/local/....
        return CaptchaServiceFactory.getCache(config.getCacheType().name());
    }
}
