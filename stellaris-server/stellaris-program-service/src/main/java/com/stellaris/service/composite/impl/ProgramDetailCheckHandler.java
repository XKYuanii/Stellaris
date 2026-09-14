package com.stellaris.service.composite.impl;


import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.BusinessStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.service.ProgramService;
import com.stellaris.service.composite.AbstractProgramCheckHandler;
import com.stellaris.vo.ProgramVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目检查
 * @author: xz_y
 **/
@Component
public class ProgramDetailCheckHandler extends AbstractProgramCheckHandler {
    
    @Autowired
    private ProgramService programService;

    public void validate(ProgramOrderCreateDto programOrderCreateDto) {
        execute(programOrderCreateDto);
    }
    
    @Override
    protected void execute(final ProgramOrderCreateDto programOrderCreateDto) {
        ProgramVo programVo = Optional.ofNullable(
                        programService.simpleGetProgramAndShowMultipleCache(programOrderCreateDto.getProgramId()))
                .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST));
        if (!Objects.equals(programVo.getProgramStatus(), BusinessStatus.YES.getCode())
                || programVo.getIssueTime() != null && programVo.getIssueTime().after(new java.util.Date())) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_ON_SALE);
        }
        if (programVo.getShowTime() != null && !programVo.getShowTime().after(new java.util.Date())) {
            throw new StellarisFrameException(BaseCode.PROGRAM_SALE_ENDED);
        }
        if (Objects.equals(programVo.getPermitChooseSeat(), BusinessStatus.NO.getCode())
                && Objects.nonNull(programOrderCreateDto.getSeatDtoList())) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_ALLOW_CHOOSE_SEAT);
        }
        Integer seatCount = Optional.ofNullable(programOrderCreateDto.getSeatDtoList()).map(List::size).orElse(0);
        Integer ticketCount = Optional.ofNullable(programOrderCreateDto.getTicketCount()).orElse(0);
        int requestedCount = seatCount != 0 ? seatCount : ticketCount;
        if (programVo.getPerOrderLimitPurchaseCount() != null
                && requestedCount > programVo.getPerOrderLimitPurchaseCount()) {
            throw new StellarisFrameException(BaseCode.PER_ORDER_PURCHASE_COUNT_OVER_LIMIT);
        }
        programOrderCreateDto.setServerAccountLimit(
                Optional.ofNullable(programVo.getPerAccountLimitPurchaseCount()).orElse(0));
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
