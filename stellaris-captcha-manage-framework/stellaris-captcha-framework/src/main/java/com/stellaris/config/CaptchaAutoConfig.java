package com.stellaris.config;

import com.stellaris.properties.AjCaptchaProperties;
import com.stellaris.captcha.service.CaptchaCacheService;
import com.stellaris.captcha.service.CaptchaService;
import com.stellaris.captcha.service.impl.CaptchaServiceFactory;
import com.stellaris.service.CaptchaCacheServiceRedisImpl;
import com.stellaris.service.CaptchaHandle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 验证码配置
 * @author: 阿星不是程序员
 **/
public class CaptchaAutoConfig {
    
    @Bean
    public CaptchaHandle captchaHandle(CaptchaService captchaService){
        return new CaptchaHandle(captchaService);
    }
    
    @Bean(name = "AjCaptchaCacheService")
    @Primary
    public CaptchaCacheService captchaCacheService(AjCaptchaProperties config, StringRedisTemplate redisTemplate){
        //缓存类型redis/local/....
        CaptchaCacheService ret = CaptchaServiceFactory.getCache(config.getCacheType().name());
        if(ret instanceof CaptchaCacheServiceRedisImpl){
            ((CaptchaCacheServiceRedisImpl)ret).setStringRedisTemplate(redisTemplate);
        }
        return ret;
    }
}
