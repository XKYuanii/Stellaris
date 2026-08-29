package com.stellaris.service.composite.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.stellaris.client.UserClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.ProgramGetDto;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.ProgramService;
import com.stellaris.service.composite.AbstractProgramCheckHandler;
import com.stellaris.service.tool.TokenExpireManager;
import com.stellaris.vo.ProgramVo;
import com.stellaris.vo.TicketUserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户检查
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class ProgramUserExistCheckHandler extends AbstractProgramCheckHandler {
    
    @Autowired
    private UserClient userClient;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private TokenExpireManager tokenExpireManager;
    
    @Override
    protected void execute(ProgramOrderCreateDto programOrderCreateDto) {
        List<TicketUserVo> ticketUserVoList = redisCache.getValueIsList(RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_USER_LIST, programOrderCreateDto.getUserId()), TicketUserVo.class);
        if (CollectionUtil.isEmpty(ticketUserVoList)) {
            TicketUserListDto ticketUserListDto = new TicketUserListDto();
            ticketUserListDto.setUserId(programOrderCreateDto.getUserId());
            ApiResponse<List<TicketUserVo>> apiResponse = userClient.list(ticketUserListDto);
            if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                ticketUserVoList = apiResponse.getData();
            }else {
                log.error("user client rpc getUserAndTicketUserList select response : {}", JSON.toJSONString(apiResponse));
                throw new StellarisFrameException(apiResponse);
            }
        }
        if (CollectionUtil.isEmpty(ticketUserVoList)) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_EMPTY);
        }
        Map<Long, TicketUserVo> ticketUserVoMap = ticketUserVoList.stream()
                .collect(Collectors.toMap(TicketUserVo::getId, ticketUserVo -> ticketUserVo, (v1, v2) -> v2));
        for (Long ticketUserId : programOrderCreateDto.getTicketUserIdList()) {
            if (Objects.isNull(ticketUserVoMap.get(ticketUserId))) {
                throw new StellarisFrameException(BaseCode.TICKET_USER_EMPTY);
            }
        }
        ProgramGetDto programGetDto = new ProgramGetDto();
        programGetDto.setId(programOrderCreateDto.getProgramId());
        ProgramVo programVo = programService.detailV2(programGetDto);
        if (Objects.isNull(programVo)) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
        if (!Objects.equals(programVo.getProgramStatus(), 1)
                || programVo.getIssueTime() != null && programVo.getIssueTime().after(new java.util.Date())) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_ON_SALE);
        }
        if (programVo.getShowTime() != null && !programVo.getShowTime().after(new java.util.Date())) {
            throw new StellarisFrameException(BaseCode.PROGRAM_SALE_ENDED);
        }
        if (CollectionUtil.isNotEmpty(programOrderCreateDto.getSeatDtoList())
                && !Objects.equals(programVo.getPermitChooseSeat(), 1)) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_ALLOW_CHOOSE_SEAT);
        }

        Integer seatCount = Optional.ofNullable(programOrderCreateDto.getSeatDtoList()).map(List::size).orElse(0);

        Integer ticketCount = Optional.ofNullable(programOrderCreateDto.getTicketCount()).orElse(0);
        int requestedCount = seatCount != 0 ? seatCount : ticketCount;
        if (programVo.getPerOrderLimitPurchaseCount() != null
                && requestedCount > programVo.getPerOrderLimitPurchaseCount()) {
            throw new StellarisFrameException(BaseCode.PER_ORDER_PURCHASE_COUNT_OVER_LIMIT);
        }
        // 服务端节目缓存提供限额，真正的“校验 + 占用”在锁座 Lua 内原子完成，避免前置查订单库的 TOCTOU。
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
        return 2;
    }
}
