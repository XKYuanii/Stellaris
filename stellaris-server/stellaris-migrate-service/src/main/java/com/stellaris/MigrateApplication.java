package com.stellaris;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 迁移服务启动
 * @author: 阿星不是程序员
 **/
@MapperScan({"com.stellaris.mapper"})
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class MigrateApplication {

    public static void main(String[] args) {
        System.setProperty("nacos.logging.default.config.enabled","false");
        SpringApplication.run(MigrateApplication.class, args);
    }

}
