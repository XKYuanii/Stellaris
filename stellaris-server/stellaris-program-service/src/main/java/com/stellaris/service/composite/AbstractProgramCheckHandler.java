package com.stellaris.service.composite;

import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.initialize.impl.composite.AbstractComposite;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 生成节目订单验证基类，生成节目订单的相关验证逻辑继承此类
 * @author: xz_y
 **/
public abstract class AbstractProgramCheckHandler extends AbstractComposite<ProgramOrderCreateDto> {
    
    @Override
    public String type() {
        return CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue();
    }
}
