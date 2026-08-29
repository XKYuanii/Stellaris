package com.stellaris.properties;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.util.StringUtil;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 后台管理配置属性
 * @author: 阿星不是程序员
 **/
public class ApiVerify {
    
    private final String apiPassword = "api_password";
    
    private final BackManageProperties backManageProperties;
    
    public ApiVerify(BackManageProperties backManageProperties) {
        this.backManageProperties = backManageProperties;
    }
    
    public void verifyApi() {
        if (backManageProperties.getApiPasswordCall()) {
            String password = Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                    .map(requestAttributes -> ((ServletRequestAttributes) requestAttributes).getRequest())
                    .map(request -> request.getHeader(apiPassword))
                    .orElseGet(() -> null);
            if (StringUtil.isEmpty(password)) {
                throw new StellarisFrameException(BaseCode.API_CALL_NEED_PASSWORD);
            }
            if (!password.equals(backManageProperties.getApiPassword())) {
                throw new StellarisFrameException(BaseCode.API_CALL_PASSWORD_ERROR);
            }
        }
    }
}
