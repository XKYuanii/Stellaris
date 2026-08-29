package com.stellaris.service.composite.register;


import com.stellaris.dto.UserRegisterDto;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.initialize.impl.composite.AbstractComposite;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户注册验证基类，用户注册的相关验证逻辑继承此类
 * @author: 阿星不是程序员
 **/
public abstract class AbstractUserRegisterCheckHandler extends AbstractComposite<UserRegisterDto> {
    
    @Override
    public String type() {
        return CompositeCheckType.USER_REGISTER_CHECK.getValue();
    }
}
