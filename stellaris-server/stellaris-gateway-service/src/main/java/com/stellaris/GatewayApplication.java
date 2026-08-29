package com.stellaris;

import com.stellaris.context.config.GatewayContextAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: gateway网关服务启动
 * @author: 阿星不是程序员
 **/
@EnableDiscoveryClient
@EnableFeignClients
@Import(GatewayContextAutoConfiguration.class)
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(GatewayApplication.class, args);
    }

}
