package com.stellaris.service.strategy.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.stellaris.core.RepeatExecuteLimitConstants;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.SeatDto;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.enums.ProgramOrderVersion;
import com.stellaris.initialize.impl.composite.CompositeContainer;
import com.stellaris.locallock.LocalLockCache;
import com.stellaris.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.stellaris.service.ProgramOrderService;
import com.stellaris.service.strategy.ProgramOrderStrategy;
import com.stellaris.servicelock.LockType;
import com.stellaris.util.ServiceLockTool;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.stellaris.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V2;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单v2
 * @author: xz_y
 **/
@Slf4j
@Component
public class ProgramOrderV21Strategy implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    @Autowired
    private LocalLockCache localLockCache;
    
    
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId","#programOrderCreateDto.programId"})
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(),programOrderCreateDto);
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketCategoryIdList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            ticketCategoryIdList =
                    seatDtoList.stream().map(SeatDto::getTicketCategoryId).distinct().sorted().collect(Collectors.toList());
        }else {
            ticketCategoryIdList.add(programOrderCreateDto.getTicketCategoryId());
        }
        List<RLock> serviceLockList = new ArrayList<>(ticketCategoryIdList.size());
        List<RLock> serviceLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        for (Long ticketCategoryId : ticketCategoryIdList) {
            String lockKey = StrUtil.join("-",PROGRAM_ORDER_CREATE_V2,
                    programOrderCreateDto.getProgramId(),ticketCategoryId);
            ReentrantLock localLock = localLockCache.getLock(lockKey,false);
            RLock serviceLock = serviceLockTool.getLock(LockType.Reentrant, lockKey);
            serviceLockList.add(serviceLock);
        }
        for (RLock rLock : serviceLockList) {
            try {
                rLock.lock();
            }catch (Throwable t) {
                break;
            }
            serviceLockSuccessList.add(rLock);
        }
        try {
            return programOrderService.create(programOrderCreateDto,ProgramOrderVersion.V21_VERSION.getValue());
        }finally {
            for (int i = serviceLockSuccessList.size() - 1; i >= 0; i--) {
                RLock rLock = serviceLockSuccessList.get(i);
                try {
                    rLock.unlock();
                }catch (Throwable t) {
                    log.error("service lock unlock error",t);
                }
            }
        }
    }
    
    @Override
    public String version() {
        return ProgramOrderVersion.V21_VERSION.getVersion();
    }
}
