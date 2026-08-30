package com.stellaris.service.strategy.impl;

import com.stellaris.core.RepeatExecuteLimitConstants;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.enums.ProgramOrderVersion;
import com.stellaris.initialize.impl.composite.CompositeContainer;
import com.stellaris.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.stellaris.service.ProgramOrderService;
import com.stellaris.service.strategy.BaseProgramOrder;
import com.stellaris.service.strategy.ProgramOrderStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单v3
 * @author: xz_y
 **/
@Slf4j
@Component
public class ProgramOrderV31Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private BaseProgramOrder baseProgramOrder;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId","#programOrderCreateDto.programId"})
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(),programOrderCreateDto);
        return programOrderService.createNew(programOrderCreateDto,ProgramOrderVersion.V31_VERSION.getValue());
    }
    
    @Override
    public String version() {
        return ProgramOrderVersion.V31_VERSION.getVersion();
    }
}
