package com.stellaris.service.composite;

import com.stellaris.dto.ProgramRecommendListDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.initialize.impl.composite.AbstractComposite;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目推荐参数验证
 * @author: xz_y
 **/
@Component
public class ProgramRecommendCheckHandler extends AbstractComposite<ProgramRecommendListDto> {
    
    @Override
    protected void execute(final ProgramRecommendListDto param) {
        if (Objects.isNull(param.getAreaId()) && 
                Objects.isNull(param.getParentProgramCategoryId()) &&
                Objects.isNull(param.getProgramId())) {
            throw new StellarisFrameException(BaseCode.PARAMETERS_CANNOT_BE_EMPTY);
        }
    }
    
    @Override
    public String type() {
        return CompositeCheckType.PROGRAM_RECOMMEND_CHECK.getValue();
    }
    
    @Override
    public Integer executeParentOrder() {
        return 0;
    }
    
    @Override
    public Integer executeTier() {
        return 1;
    }
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
}
