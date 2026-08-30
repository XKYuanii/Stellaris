package com.stellaris.service.composite.register.impl;

import com.stellaris.dto.UserRegisterDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.service.composite.register.AbstractUserRegisterCheckHandler;
import com.stellaris.service.tool.RequestCounter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户注册请求数检查
 * @author: xz_y
 **/
@Component
public class UserRegisterCountCheckHandler extends AbstractUserRegisterCheckHandler {
    
    @Autowired
    private RequestCounter requestCounter;
    
    @Override
    protected void execute(final UserRegisterDto param) {
        boolean result = requestCounter.onRequest();
        if (result) {
            throw new StellarisFrameException(BaseCode.USER_REGISTER_FREQUENCY);
        }
    }
    
    @Override
    public Integer executeParentOrder() {
        return 1;
    }
    
    @Override
    public Integer executeTier() {
        return 2;
    }
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
}
