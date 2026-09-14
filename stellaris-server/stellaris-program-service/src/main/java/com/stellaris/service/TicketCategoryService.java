package com.stellaris.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.client.OrderClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.SeatInventoryCountDto;
import com.stellaris.dto.TicketCategoryAddDto;
import com.stellaris.dto.TicketCategoryDto;
import com.stellaris.dto.TicketCategoryListByProgramDto;
import com.stellaris.dto.TicketCategoryListDto;
import com.stellaris.entity.Program;
import com.stellaris.entity.TicketCategory;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.ProgramMapper;
import com.stellaris.mapper.TicketCategoryMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.cache.local.LocalCacheTicketCategory;
import com.stellaris.service.reference.SeatReservationKeys;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.util.DateUtils;
import com.stellaris.util.ServiceLockTool;
import com.stellaris.vo.TicketCategoryDetailVo;
import com.stellaris.vo.TicketCategoryVo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.stellaris.core.DistributedLockConstants.GET_TICKET_CATEGORY_LOCK;
import static com.stellaris.core.DistributedLockConstants.TICKET_CATEGORY_LOCK;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 票档 service
 * @author: xz_y
 **/
@Slf4j
@Service
public class TicketCategoryService extends ServiceImpl<TicketCategoryMapper, TicketCategory> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private LocalCacheTicketCategory localCacheTicketCategory;
    
    @Autowired
    private ProgramMapper programMapper;

    @Autowired
    private OrderClient orderClient;
    
    @Transactional(rollbackFor = Exception.class)
    public Long add(TicketCategoryAddDto ticketCategoryAddDto) {
        Program program = programMapper.selectById(ticketCategoryAddDto.getProgramId());
        if (Objects.isNull(program)) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
        TicketCategory ticketCategory = new TicketCategory();
        BeanUtil.copyProperties(ticketCategoryAddDto,ticketCategory);
        ticketCategory.setId(uidGenerator.getUid());
        ticketCategoryMapper.insert(ticketCategory);
        return ticketCategory.getId();
    }
    
    public List<TicketCategoryVo> selectTicketCategoryListByProgramIdMultipleCache(Long programId, Date showTime){
        return localCacheTicketCategory.getCache(programId,key -> selectTicketCategoryListByProgramId(programId, 
                DateUtils.countBetweenSecond(DateUtils.now(),showTime), TimeUnit.SECONDS));
    }
    
    @ServiceLock(lockType= LockType.Read,name = TICKET_CATEGORY_LOCK,keys = {"#programId"})
    public List<TicketCategoryVo> selectTicketCategoryListByProgramId(Long programId,Long expireTime,TimeUnit timeUnit){
        List<TicketCategoryVo> ticketCategoryVoList = 
                redisCache.getValueIsList(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, 
                        programId), TicketCategoryVo.class);
        if (CollectionUtil.isNotEmpty(ticketCategoryVoList)) {
            return ticketCategoryVoList;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_TICKET_CATEGORY_LOCK, 
                new String[]{String.valueOf(programId)});
        lock.lock();
        try {
            return redisCache.getValueIsList(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, programId),
                    TicketCategoryVo.class,
                    () -> {
                        LambdaQueryWrapper<TicketCategory> ticketCategoryLambdaQueryWrapper =
                                Wrappers.lambdaQuery(TicketCategory.class).eq(TicketCategory::getProgramId, programId);
                        List<TicketCategory> ticketCategoryList =
                                ticketCategoryMapper.selectList(ticketCategoryLambdaQueryWrapper);
                        return ticketCategoryList.stream().map(ticketCategory -> {
                            TicketCategoryVo ticketCategoryVo = new TicketCategoryVo();
                            BeanUtil.copyProperties(ticketCategory, ticketCategoryVo);
                            return ticketCategoryVo;
                        }).collect(Collectors.toList());
                    }, expireTime, timeUnit);
        }finally {
            lock.unlock();
        }
    }
    
    public Map<String, Long> getRedisRemainNumberResolution(Long programId,Long ticketCategoryId){
        return Map.of(String.valueOf(ticketCategoryId), availableCount(programId, ticketCategoryId));
    }
    
    public TicketCategoryDetailVo detail(TicketCategoryDto ticketCategoryDto) {
        TicketCategory ticketCategory = ticketCategoryMapper.selectById(ticketCategoryDto.getId());
        TicketCategoryDetailVo ticketCategoryDetailVo = new TicketCategoryDetailVo();
        BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailVo);
        ticketCategoryDetailVo.setRemainNumber(availableCount(
                ticketCategory.getProgramId(), ticketCategory.getId()));
        return ticketCategoryDetailVo;
    }

    public List<TicketCategoryDetailVo> selectList(TicketCategoryListDto ticketCategoryDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, ticketCategoryDto.getProgramId())
                .in(TicketCategory::getId, ticketCategoryDto.getTicketCategoryIdList()));
        Map<Long, Long> availableCounts = availableCounts(ticketCategorieList);
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDetailVo ticketCategoryDetailVo = new TicketCategoryDetailVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailVo);
            ticketCategoryDetailVo.setRemainNumber(availableCounts.getOrDefault(ticketCategory.getId(), 0L));
            return ticketCategoryDetailVo;
        }).collect(Collectors.toList());
    }

    public List<TicketCategoryDetailVo> selectListByProgram(TicketCategoryListByProgramDto ticketCategoryListByProgramDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, ticketCategoryListByProgramDto.getProgramId()));
        Map<Long, Long> availableCounts = availableCounts(ticketCategorieList);
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDetailVo ticketCategoryDetailVo = new TicketCategoryDetailVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailVo);
            ticketCategoryDetailVo.setRemainNumber(availableCounts.getOrDefault(ticketCategory.getId(), 0L));
            return ticketCategoryDetailVo;
        }).collect(Collectors.toList());
    }

    /**
     * 同一节目票档列表在 Redis 就绪后用 pipeline 批量执行 ZCARD，避免展示接口按票档逐次往返。
     * 快照未就绪属于低频管理阶段，仍以交易库逐档回源，保证读到权威库存。
     */
    private Map<Long, Long> availableCounts(List<TicketCategory> categories) {
        if (CollectionUtil.isEmpty(categories)) {
            return Map.of();
        }
        Long programId = categories.get(0).getProgramId();
        if (!Boolean.TRUE.equals(redisCache.getInstance().hasKey(SeatReservationKeys.ready(programId)))) {
            return categories.stream().collect(Collectors.toMap(
                    TicketCategory::getId,
                    category -> tradeAvailableCount(category.getProgramId(), category.getId()),
                    (left, right) -> left,
                    LinkedHashMap::new));
        }

        StringRedisTemplate template = (StringRedisTemplate) redisCache.getInstance();
        List<Object> results = template.executePipelined((RedisCallback<Object>) connection -> {
            for (TicketCategory category : categories) {
                byte[] key = template.getStringSerializer().serialize(
                        SeatReservationKeys.available(category.getProgramId(), category.getId()));
                connection.zSetCommands().zCard(key);
            }
            return null;
        });
        Map<Long, Long> counts = new LinkedHashMap<>(categories.size());
        for (int index = 0; index < categories.size(); index++) {
            Object result = index < results.size() ? results.get(index) : null;
            counts.put(categories.get(index).getId(), result instanceof Number ? ((Number) result).longValue() : 0L);
        }
        return counts;
    }

    /** 开售后的展示读 Redis 可售集合；快照尚未就绪时才回源交易库。 */
    private long availableCount(Long programId, Long ticketCategoryId) {
        if (Boolean.TRUE.equals(redisCache.getInstance().hasKey(SeatReservationKeys.ready(programId)))) {
            Long count = redisCache.getInstance().opsForZSet()
                    .size(SeatReservationKeys.available(programId, ticketCategoryId));
            return count == null ? 0L : count;
        }
        return tradeAvailableCount(programId, ticketCategoryId);
    }

    private long tradeAvailableCount(Long programId, Long ticketCategoryId) {
        SeatInventoryCountDto dto = new SeatInventoryCountDto();
        dto.setProgramId(programId);
        dto.setTicketCategoryId(ticketCategoryId);
        ApiResponse<Long> response = orderClient.availableSeatCount(dto);
        if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())
                || response.getData() == null) {
            throw new IllegalStateException("trade inventory count is unavailable");
        }
        return response.getData();
    }
}
