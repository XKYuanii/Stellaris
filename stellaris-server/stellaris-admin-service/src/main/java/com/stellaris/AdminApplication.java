package com.stellaris;

import com.stellaris.config.StellarisCommonAutoConfig;
import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 监控服务启动
 * @author: 阿星不是程序员
 **/
@EnableAdminServer
@EnableDiscoveryClient
@SpringBootApplication(exclude = StellarisCommonAutoConfig.class)
public class AdminApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(AdminApplication.class, args);
    }

}
