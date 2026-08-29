package com.stellaris.config;

import com.stellaris.monitor.DingTalkMessage;
import com.stellaris.monitor.MonitorServer;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 服务监控 配置
 * @author: 阿星不是程序员
 **/
@Configuration
public class MonitorServerConfig {
    
    @Value("${dingtalk.token:}")
    private String token;
    
    @Bean
    public DingTalkMessage dingTalkMessage(){
        return new DingTalkMessage(token);
    }
    
    @Bean
    public MonitorServer monitorServer(DingTalkMessage dingTalkMessage,InstanceRepository repository){
        return new MonitorServer(dingTalkMessage,repository);
    }
    
    
}
