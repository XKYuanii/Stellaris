package com.stellaris.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 后台管理配置属性
 * @author: 阿星不是程序员
 **/
@Data
@ConfigurationProperties(prefix = BackManageProperties.MANAGE)
public class BackManageProperties {
    
    public static final String MANAGE = "manage";
    
    private String username = "admin";
    
    private String password = "admin";
    
    private List<String> loginExcludeApi = List.of("/auth/login");
    
    private Boolean apiPasswordCall = false;
    
    private String apiPassword;
}
