package com.stellaris.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.ProgramShowTimeAddDto;
import com.stellaris.entity.Program;
import com.stellaris.entity.ProgramGroup;
import com.stellaris.entity.ProgramShowTime;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.ProgramGroupMapper;
import com.stellaris.mapper.ProgramMapper;
import com.stellaris.mapper.ProgramShowTimeMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.cache.local.LocalCacheProgramShowTime;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.util.DateUtils;
import com.stellaris.util.ServiceLockTool;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.stellaris.core.DistributedLockConstants.GET_PROGRAM_SHOW_TIME_LOCK;
import static com.stellaris.core.DistributedLockConstants.PROGRAM_SHOW_TIME_LOCK;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目演出时间 service
 * @author: xz_y
 **/
@Service
public class ProgramShowTimeService extends ServiceImpl<ProgramShowTimeMapper, ProgramShowTime> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private ProgramMapper programMapper;
    
    @Autowired
    private ProgramShowTimeMapper programShowTimeMapper;
    
    @Autowired
    private ProgramGroupMapper programGroupMapper;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private LocalCacheProgramShowTime localCacheProgramShowTime;
    
    
    @Transactional(rollbackFor = Exception.class)
    public Long add(ProgramShowTimeAddDto programShowTimeAddDto) {
        ProgramShowTime programShowTime = new ProgramShowTime();
        BeanUtil.copyProperties(programShowTimeAddDto,programShowTime);
        programShowTime.setId(uidGenerator.getUid());
        programShowTimeMapper.insert(programShowTime);
        return programShowTime.getId();
    }
    
    public ProgramShowTime selectProgramShowTimeByProgramIdMultipleCache(Long programId){
        return localCacheProgramShowTime.getCache(RedisKeyBuild.createRedisKey
                (RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey(),
                key -> selectProgramShowTimeByProgramId(programId));
    }
    
    public ProgramShowTime simpleSelectProgramShowTimeByProgramIdMultipleCache(Long programId){
        ProgramShowTime programShowTimeCache = localCacheProgramShowTime.getCache(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey());
        if (Objects.nonNull(programShowTimeCache)) {
            return programShowTimeCache;
        }
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME,
                programId), ProgramShowTime.class);
    }
    
    @ServiceLock(lockType= LockType.Read,name = PROGRAM_SHOW_TIME_LOCK,keys = {"#programId"})
    public ProgramShowTime selectProgramShowTimeByProgramId(Long programId){
        ProgramShowTime programShowTime = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, 
                programId), ProgramShowTime.class);
        if (Objects.nonNull(programShowTime)) {
            return programShowTime;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_SHOW_TIME_LOCK, 
                new String[]{String.valueOf(programId)});
        lock.lock();
        try {
            programShowTime = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME,
                    programId), ProgramShowTime.class);
            if (Objects.isNull(programShowTime)) {
                LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper =
                        Wrappers.lambdaQuery(ProgramShowTime.class).eq(ProgramShowTime::getProgramId, programId);
                programShowTime = Optional.ofNullable(programShowTimeMapper.selectOne(programShowTimeLambdaQueryWrapper))
                        .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_SHOW_TIME_NOT_EXIST));
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId),programShowTime
                        ,DateUtils.countBetweenSecond(DateUtils.now(),programShowTime.getShowTime()),TimeUnit.SECONDS);
            }
            return programShowTime;
        }finally {
            lock.unlock();   
        }
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Set<Long> renewal(){
        Set<Long> programIdSet = new HashSet<>();
        //查询演出时间小于2天前的节目演出数据
        LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper =
                Wrappers.lambdaQuery(ProgramShowTime.class).
                        le(ProgramShowTime::getShowTime, DateUtils.addDay(DateUtils.now(), 2));
        List<ProgramShowTime> programShowTimes = programShowTimeMapper.selectList(programShowTimeLambdaQueryWrapper);
        
        List<ProgramShowTime> newProgramShowTimes = new ArrayList<>(programShowTimes.size());
        
        for (ProgramShowTime programShowTime : programShowTimes) {
            //要更新演出时间的节目id
            programIdSet.add(programShowTime.getProgramId());
            //记录中现有的演出时间
            Date oldShowTime = programShowTime.getShowTime();
            //将现有的演出时间加上一个月作为新的演出时间
            Date newShowTime = DateUtils.addMonth(oldShowTime, 1);
            //当前时间
            Date nowDateTime = DateUtils.now();
            //如果新的演出时间还是小于当前时间，则继续再加一个月，直到新的演出时间大于当前时间为止
            while (newShowTime.before(nowDateTime)) {
                newShowTime = DateUtils.addMonth(newShowTime, 1);
            }
            //构建要更新的数据
            Date newShowDayTime = DateUtils.parseDateTime(DateUtils.formatDate(newShowTime) + " 00:00:00");
            ProgramShowTime updateProgramShowTime = new ProgramShowTime();
            updateProgramShowTime.setShowTime(newShowTime);
            updateProgramShowTime.setShowDayTime(newShowDayTime);
            updateProgramShowTime.setShowWeekTime(DateUtils.getWeekStr(newShowTime));
            LambdaUpdateWrapper<ProgramShowTime> programShowTimeLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(ProgramShowTime.class)
                            .eq(ProgramShowTime::getProgramId, programShowTime.getProgramId())
                            .eq(ProgramShowTime::getId,programShowTime.getId());
            //进行数据库更新节目演出时间        
            programShowTimeMapper.update(updateProgramShowTime,programShowTimeLambdaUpdateWrapper);
            
            ProgramShowTime newProgramShowTime = new ProgramShowTime();
            newProgramShowTime.setProgramId(programShowTime.getProgramId());
            newProgramShowTime.setShowTime(newShowTime);
            newProgramShowTimes.add(newProgramShowTime);
        }
        //节目组最近演出时间更新
        Map<Long,Date> programGroupMap = new HashMap<>(newProgramShowTimes.size());
        for (ProgramShowTime newProgramShowTime : newProgramShowTimes) {
            Program program = programMapper.selectById(newProgramShowTime.getProgramId());
            if (Objects.isNull(program)) {
                continue;
            }
            Long programGroupId = program.getProgramGroupId();
            Date showTime = programGroupMap.get(programGroupId);
            if (Objects.isNull(showTime)) {
                //如果programGroupMap中没有节目演出时间，则直接放入
                programGroupMap.put(programGroupId,newProgramShowTime.getShowTime());
            }else {
                //如果programGroupMap中有节目演出时间，则比较现有的和新的那个更小，取更小的
                if (DateUtil.compare(newProgramShowTime.getShowTime(),showTime) < 0) {
                    programGroupMap.put(programGroupId,newProgramShowTime.getShowTime());
                }
            }
        }
        if (CollectionUtil.isNotEmpty(programGroupMap)) {
            programGroupMap.forEach((k,v) -> {
                ProgramGroup programGroup = new ProgramGroup();
                programGroup.setRecentShowTime(v);
                
                LambdaUpdateWrapper<ProgramGroup> programGroupLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(ProgramGroup.class)
                                .eq(ProgramGroup::getId,k);
                //进行数据库更新节目组最近演出时间
                programGroupMapper.update(programGroup,programGroupLambdaUpdateWrapper);
            });
        }
        //返回更新了演出时间的节目id集合
        return programIdSet;
    }
}
