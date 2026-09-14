package com.stellaris.service.composite.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.stellaris.client.UserClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.composite.AbstractProgramCheckHandler;
import com.stellaris.vo.TicketUserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户检查
 * @author: xz_y
 **/
@Slf4j
@Component
public class ProgramUserExistCheckHandler extends AbstractProgramCheckHandler {
    
    @Autowired
    private UserClient userClient;
    
    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ReactiveStringRedisTemplate reactiveRedisTemplate;

    public void validate(ProgramOrderCreateDto programOrderCreateDto) {
        execute(programOrderCreateDto);
    }

    /**
     * 热路径使用 Lettuce 非阻塞 GET；只有缓存未命中时才把 Feign 回源放到有界弹性线程池。
     */
    public Mono<Boolean> validateAsync(ProgramOrderCreateDto programOrderCreateDto) {
        String key = RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_USER_LIST, programOrderCreateDto.getUserId()).getRelKey();
        return reactiveRedisTemplate.opsForValue().get(key)
                .map(value -> JSON.parseArray(value, TicketUserVo.class))
                .switchIfEmpty(Mono.fromCallable(() -> loadTicketUsers(programOrderCreateDto.getUserId()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(ticketUsers -> {
                    validateTicketUsers(programOrderCreateDto, ticketUsers);
                    return Boolean.TRUE;
                });
    }
    
    @Override
    protected void execute(ProgramOrderCreateDto programOrderCreateDto) {
        List<TicketUserVo> ticketUserVoList = redisCache.getValueIsList(RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_USER_LIST, programOrderCreateDto.getUserId()), TicketUserVo.class);
        if (CollectionUtil.isEmpty(ticketUserVoList)) {
            ticketUserVoList = loadTicketUsers(programOrderCreateDto.getUserId());
        }
        validateTicketUsers(programOrderCreateDto, ticketUserVoList);
    }

    private List<TicketUserVo> loadTicketUsers(Long userId) {
        TicketUserListDto ticketUserListDto = new TicketUserListDto();
        ticketUserListDto.setUserId(userId);
        ApiResponse<List<TicketUserVo>> apiResponse = userClient.list(String.valueOf(userId), ticketUserListDto);
        if (!Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            log.error("user client rpc getUserAndTicketUserList select response : {}", JSON.toJSONString(apiResponse));
            throw new StellarisFrameException(apiResponse);
        }
        return apiResponse.getData();
    }

    private void validateTicketUsers(ProgramOrderCreateDto programOrderCreateDto,
                                     List<TicketUserVo> ticketUserVoList) {
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
