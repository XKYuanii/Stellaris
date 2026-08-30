package com.stellaris.conf;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: elasticsearch配置属性
 * @author: xz_y
 **/
@Data
@ConfigurationProperties(prefix = BusinessEsProperties.PREFIX)
public class BusinessEsProperties {
    
    public static final String PREFIX = "elasticsearch";
    
    private String[] ip;
    
    private String userName;
    
    private String passWord;
    
    private Boolean esSwitch = true;
    
    private Boolean esTypeSwitch = false;
    
    private Integer connectTimeOut = 40000;
    
    private Integer socketTimeOut = 40000;
    
    private Integer connectionRequestTimeOut = 40000;
    
    private Integer maxConnectNum = 400;

    /**
     * Elasticsearch HTTP async client I/O reactor threads. This is deliberately
     * independent from maxConnectNum: connections are multiplexed by a small
     * number of selector threads and must not create one thread per connection.
     */
    private Integer ioThreadCount = 4;
}
