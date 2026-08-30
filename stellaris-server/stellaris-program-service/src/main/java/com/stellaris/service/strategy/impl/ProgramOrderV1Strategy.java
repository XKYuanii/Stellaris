package com.stellaris.service.strategy.impl;

import com.stellaris.core.RepeatExecuteLimitConstants;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.enums.ProgramOrderVersion;
import com.stellaris.initialize.impl.composite.CompositeContainer;
import com.stellaris.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.stellaris.service.ProgramOrderService;
import com.stellaris.service.strategy.ProgramOrderStrategy;
import com.stellaris.servicelock.annotion.ServiceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.stellaris.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V1;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单v1
 * @author: xz_y
 **/
@Component
public class ProgramOrderV1Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId","#programOrderCreateDto.programId"})
    @ServiceLock(name = PROGRAM_ORDER_CREATE_V1,keys = {"#programOrderCreateDto.programId"})
    @Override
    public String createOrder(final ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(),programOrderCreateDto);
        return programOrderService.create(programOrderCreateDto,ProgramOrderVersion.V1_VERSION.getValue());
    }
    
    @Override
    public String version() {
        return ProgramOrderVersion.V1_VERSION.getVersion();
    }
}
