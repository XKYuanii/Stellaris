package com.stellaris.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.BusinessThreadPool;
import com.stellaris.RedisStreamPushHandler;
import com.stellaris.client.BaseDataClient;
import com.stellaris.client.OrderClient;
import com.stellaris.client.UserClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.AreaGetDto;
import com.stellaris.dto.AreaSelectDto;
import com.stellaris.dto.ProgramAddDto;
import com.stellaris.dto.ProgramDataPreheatDto;
import com.stellaris.dto.ProgramGetDto;
import com.stellaris.dto.ProgramInvalidDto;
import com.stellaris.dto.ProgramListDto;
import com.stellaris.dto.ProgramOperateDataDto;
import com.stellaris.dto.ProgramPageListDto;
import com.stellaris.dto.ProgramRecommendListDto;
import com.stellaris.dto.ProgramResetExecuteDto;
import com.stellaris.dto.ProgramSearchDto;
import com.stellaris.dto.ReduceRemainNumberDto;
import com.stellaris.dto.TicketCategoryCountDto;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.entity.Program;
import com.stellaris.entity.OrderInventoryOperation;
import com.stellaris.entity.ProgramCategory;
import com.stellaris.entity.ProgramGroup;
import com.stellaris.entity.ProgramJoinShowTime;
import com.stellaris.entity.ProgramShowTime;
import com.stellaris.entity.Seat;
import com.stellaris.entity.TicketCategory;
import com.stellaris.entity.TicketCategoryAggregate;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.BusinessStatus;
import com.stellaris.enums.CompositeCheckType;
import com.stellaris.enums.ProgramOrderVersion;
import com.stellaris.enums.SellStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.handler.BloomFilterHandler;
import com.stellaris.initialize.impl.composite.CompositeContainer;
import com.stellaris.mapper.ProgramCategoryMapper;
import com.stellaris.mapper.ProgramGroupMapper;
import com.stellaris.mapper.ProgramMapper;
import com.stellaris.mapper.ProgramShowTimeMapper;
import com.stellaris.mapper.OrderInventoryOperationMapper;
import com.stellaris.mapper.SeatMapper;
import com.stellaris.mapper.TicketCategoryMapper;
import com.stellaris.page.PageUtil;
import com.stellaris.page.PageVo;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.stellaris.service.cache.local.LocalCacheProgram;
import com.stellaris.service.cache.local.LocalCacheProgramCategory;
import com.stellaris.service.cache.local.LocalCacheProgramGroup;
import com.stellaris.service.cache.local.LocalCacheProgramShowTime;
import com.stellaris.service.cache.local.LocalCacheTicketCategory;
import com.stellaris.service.reference.ReferenceSeatInventoryService;
import com.stellaris.service.constant.ProgramTimeType;
import com.stellaris.service.es.ProgramEs;
import com.stellaris.service.lua.ProgramDelCacheData;
import com.stellaris.service.tool.TokenExpireManager;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.threadlocal.BaseParameterHolder;
import com.stellaris.util.DateUtils;
import com.stellaris.util.ServiceLockTool;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.AccountOrderCountVo;
import com.stellaris.vo.AreaVo;
import com.stellaris.vo.ProgramGroupVo;
import com.stellaris.vo.ProgramHomeVo;
import com.stellaris.vo.ProgramListVo;
import com.stellaris.vo.ProgramSimpleInfoVo;
import com.stellaris.vo.ProgramVo;
import com.stellaris.vo.TicketCategoryVo;
import com.stellaris.vo.TicketUserVo;
import com.stellaris.vo.SeatVo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.stellaris.constant.Constant.CODE;
import static com.stellaris.constant.Constant.USER_ID;
import static com.stellaris.core.DistributedLockConstants.GET_PROGRAM_LOCK;
import static com.stellaris.core.DistributedLockConstants.PROGRAM_GROUP_LOCK;
import static com.stellaris.core.DistributedLockConstants.PROGRAM_LOCK;
import static com.stellaris.core.DistributedLockConstants.REFERENCE_PREHEAT;
import static com.stellaris.core.RepeatExecuteLimitConstants.PAY_OR_CANCEL_PROGRAM_ORDER;
import static com.stellaris.core.RepeatExecuteLimitConstants.REDUCE_REMAIN_NUMBER;
import static com.stellaris.util.DateUtils.FORMAT_DATE;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目 service
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class ProgramService extends ServiceImpl<ProgramMapper, Program> {

    /** 仅供本地演示重置数据；正常预热绝不能改写已售/锁定座位。 */
    @Value("${program.demo-reset-enabled:false}")
    private boolean demoResetEnabled;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private ProgramMapper programMapper;
    
    @Autowired
    private ProgramGroupMapper programGroupMapper;
    
    @Autowired
    private ProgramShowTimeMapper programShowTimeMapper;
    
    @Autowired
    private ProgramCategoryMapper programCategoryMapper; 
    
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private OrderInventoryOperationMapper orderInventoryOperationMapper;
    
    @Autowired
    private SeatMapper seatMapper;
    
    @Autowired
    private BaseDataClient baseDataClient;
    
    @Autowired
    private UserClient userClient;
    
    @Autowired
    private OrderClient orderClient;
    
    @Autowired
    private RedisCache redisCache;
    
    @Lazy
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private ProgramShowTimeService programShowTimeService;
    
    @Autowired
    private TicketCategoryService ticketCategoryService;
    
    @Autowired
    private ProgramCategoryService programCategoryService;
    
    @Autowired
    private ProgramEs programEs;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private RedisStreamPushHandler redisStreamPushHandler;
    
    @Autowired
    private LocalCacheProgram localCacheProgram;
    
    @Autowired
    private LocalCacheProgramGroup localCacheProgramGroup;
    
    @Autowired
    private LocalCacheProgramCategory localCacheProgramCategory;
    
    @Autowired
    private LocalCacheProgramShowTime localCacheProgramShowTime;
    
    @Autowired
    private LocalCacheTicketCategory localCacheTicketCategory;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    @Autowired
    private TokenExpireManager tokenExpireManager;
    
    @Autowired
    private ProgramDelCacheData programDelCacheData;
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private SeatService seatService;

    @Autowired
    private ReferenceSeatInventoryService referenceSeatInventoryService;

    @Autowired
    private BloomFilterHandler bloomFilterHandler;
    
    /**
     * 添加节目
     * @param programAddDto 添加节目数据的入参
     * @return 添加节目后的id
     * */
    public Long add(ProgramAddDto programAddDto){
        Program program = new Program();
        BeanUtil.copyProperties(programAddDto,program);
        program.setId(uidGenerator.getUid());
        programMapper.insert(program);
        return program.getId();
    }
    
    /**
     * 搜索
     * @param programSearchDto 搜索节目数据的入参
     * @return 执行后的结果
     * */
    public PageVo<ProgramListVo> search(ProgramSearchDto programSearchDto) {
        //将入参的参数进行具体的组装
        setQueryTime(programSearchDto);
        return programEs.search(programSearchDto);
    }
    
    /**
     * 查询主页信息
     * @param programListDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public List<ProgramHomeVo> selectHomeList(ProgramListDto programListDto) {
        
        List<ProgramHomeVo> programHomeVoList = programEs.selectHomeList(programListDto);
        if (CollectionUtil.isNotEmpty(programHomeVoList)) {
            return programHomeVoList;
        }
        return dbSelectHomeList(programListDto);
    }
    
    /**
     * 查询主页信息（数据库查询）
     * @param programPageListDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    private List<ProgramHomeVo> dbSelectHomeList(ProgramListDto programPageListDto){
        List<ProgramHomeVo> programHomeVoList = new ArrayList<>();
        Map<Long, String> programCategoryMap = selectProgramCategoryMap(programPageListDto.getParentProgramCategoryIds());
        
        List<Program> programList = programMapper.selectHomeList(programPageListDto);
        if (CollectionUtil.isEmpty(programList)) {
            return programHomeVoList;
        }
        
        List<Long> programIdList = programList.stream().map(Program::getId).collect(Collectors.toList());
        LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper = Wrappers.lambdaQuery(ProgramShowTime.class)
                .in(ProgramShowTime::getProgramId, programIdList);
        List<ProgramShowTime> programShowTimeList = programShowTimeMapper.selectList(programShowTimeLambdaQueryWrapper);
        Map<Long, List<ProgramShowTime>> programShowTimeMap = 
                programShowTimeList.stream().collect(Collectors.groupingBy(ProgramShowTime::getProgramId));
        
        Map<Long, TicketCategoryAggregate> ticketCategorieMap = selectTicketCategorieMap(programIdList);
        
        Map<Long, List<Program>> programMap = programList.stream()
                .collect(Collectors.groupingBy(Program::getParentProgramCategoryId));
        
        for (Entry<Long, List<Program>> programEntry : programMap.entrySet()) {
            Long key = programEntry.getKey();
            List<Program> value = programEntry.getValue();
            List<ProgramListVo> programListVoList = new ArrayList<>();
            for (Program program : value) {
                ProgramListVo programListVo = new ProgramListVo();
                BeanUtil.copyProperties(program,programListVo);
                
                programListVo.setShowTime(Optional.ofNullable(programShowTimeMap.get(program.getId()))
                        .filter(list -> !list.isEmpty())
                        .map(list -> list.get(0))
                        .map(ProgramShowTime::getShowTime)
                        .orElse(null));
                programListVo.setShowDayTime(Optional.ofNullable(programShowTimeMap.get(program.getId()))
                        .filter(list -> !list.isEmpty())
                        .map(list -> list.get(0))
                        .map(ProgramShowTime::getShowDayTime)
                        .orElse(null));
                programListVo.setShowWeekTime(Optional.ofNullable(programShowTimeMap.get(program.getId()))
                        .filter(list -> !list.isEmpty())
                        .map(list -> list.get(0))
                        .map(ProgramShowTime::getShowWeekTime)
                        .orElse(null));
                
                programListVo.setMaxPrice(Optional.ofNullable(ticketCategorieMap.get(program.getId()))
                        .map(TicketCategoryAggregate::getMaxPrice).orElse(null));
                programListVo.setMinPrice(Optional.ofNullable(ticketCategorieMap.get(program.getId()))
                        .map(TicketCategoryAggregate::getMinPrice).orElse(null));
                programListVoList.add(programListVo);
            }
            ProgramHomeVo programHomeVo = new ProgramHomeVo();
            programHomeVo.setCategoryName(programCategoryMap.get(key));
            programHomeVo.setCategoryId(key);
            programHomeVo.setProgramListVoList(programListVoList);
            programHomeVoList.add(programHomeVo);
        }
        return programHomeVoList;
    }
    
    /**
     * 组装节目参数
     * @param programPageListDto 节目数据的入参
     * */
    public void setQueryTime(ProgramPageListDto programPageListDto){
        switch (programPageListDto.getTimeType()) {
            case ProgramTimeType.TODAY:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.now(FORMAT_DATE));
                break;
            case ProgramTimeType.TOMORROW:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.addDay(DateUtils.now(FORMAT_DATE),1));
                break;
            case ProgramTimeType.WEEK:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.addWeek(DateUtils.now(FORMAT_DATE),1));
                break;
            case ProgramTimeType.MONTH:
                programPageListDto.setStartDateTime(DateUtils.now(FORMAT_DATE));
                programPageListDto.setEndDateTime(DateUtils.addMonth(DateUtils.now(FORMAT_DATE),1));
                break;
            case ProgramTimeType.CALENDAR:
                if (Objects.isNull(programPageListDto.getStartDateTime())) {
                    throw new StellarisFrameException(BaseCode.START_DATE_TIME_NOT_EXIST);
                }
                if (Objects.isNull(programPageListDto.getEndDateTime())) {
                    throw new StellarisFrameException(BaseCode.END_DATE_TIME_NOT_EXIST);
                }
                break;
            default:
                programPageListDto.setStartDateTime(null);
                programPageListDto.setEndDateTime(null);
                break;
        }
    }
    
    /**
     * 查询分类列表（数据库查询）
     * @param programPageListDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public PageVo<ProgramListVo> selectPage(ProgramPageListDto programPageListDto) {
        setQueryTime(programPageListDto);
        PageVo<ProgramListVo> pageVo = programEs.selectPage(programPageListDto);
        if (CollectionUtil.isNotEmpty(pageVo.getList())) {
            return pageVo;
        }
        return dbSelectPage(programPageListDto);
    }
    
    /**
     * 推荐列表
     * @param programRecommendListDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public List<ProgramListVo> recommendList(ProgramRecommendListDto programRecommendListDto){
        compositeContainer.execute(CompositeCheckType.PROGRAM_RECOMMEND_CHECK.getValue(),programRecommendListDto);
        return programEs.recommendList(programRecommendListDto);
    }
    
    /**
     * 查询分类信息（数据库查询）
     * @param programPageListDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public PageVo<ProgramListVo> dbSelectPage(ProgramPageListDto programPageListDto) {
        IPage<ProgramJoinShowTime> iPage = 
                programMapper.selectPage(PageUtil.getPageParams(programPageListDto), programPageListDto);
        if (CollectionUtil.isEmpty(iPage.getRecords())) {
            return new PageVo<>(iPage.getCurrent(), iPage.getSize(), iPage.getTotal(), new ArrayList<>());
        }
        Set<Long> programCategoryIdList = 
                iPage.getRecords().stream().map(Program::getProgramCategoryId).collect(Collectors.toSet());
        //根据id来查询节目类型列表map，key：节目类型id，value：节目类型名
        Map<Long, String> programCategoryMap = selectProgramCategoryMap(programCategoryIdList);
        
        List<Long> programIdList = iPage.getRecords().stream().map(Program::getId).collect(Collectors.toList());
        Map<Long, TicketCategoryAggregate> ticketCategorieMap = selectTicketCategorieMap(programIdList);
        
        Map<Long,String> tempAreaMap = new HashMap<>(64);
        AreaSelectDto areaSelectDto = new AreaSelectDto();
        areaSelectDto.setIdList(iPage.getRecords().stream().map(Program::getAreaId).distinct().collect(Collectors.toList()));
        ApiResponse<List<AreaVo>> areaResponse = baseDataClient.selectByIdList(areaSelectDto);
        if (Objects.equals(areaResponse.getCode(), ApiResponse.ok().getCode())) {
            if (CollectionUtil.isNotEmpty(areaResponse.getData())) {
                tempAreaMap = areaResponse.getData().stream()
                        .collect(Collectors.toMap(AreaVo::getId,AreaVo::getName,(v1,v2) -> v2));
            }
        }else {
            log.error("base-data selectByIdList rpc error areaResponse:{}", JSON.toJSONString(areaResponse));
        }
        Map<Long,String> areaMap = tempAreaMap;
        
        return PageUtil.convertPage(iPage, programJoinShowTime -> {
            ProgramListVo programListVo = new ProgramListVo();
            BeanUtil.copyProperties(programJoinShowTime, programListVo);
            
            programListVo.setAreaName(areaMap.get(programJoinShowTime.getAreaId()));
            programListVo.setProgramCategoryName(programCategoryMap.get(programJoinShowTime.getProgramCategoryId()));
            programListVo.setMinPrice(Optional.ofNullable(ticketCategorieMap.get(programJoinShowTime.getId()))
                    .map(TicketCategoryAggregate::getMinPrice).orElse(null));
            programListVo.setMaxPrice(Optional.ofNullable(ticketCategorieMap.get(programJoinShowTime.getId()))
                    .map(TicketCategoryAggregate::getMaxPrice).orElse(null));
            return programListVo;
        });
    }
    
    /**
     * 查询节目详情
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public ProgramVo detail(ProgramGetDto programGetDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(),programGetDto);
        return getDetailV2(programGetDto);
    }
    
    /**
     * 查询节目详情V1
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public ProgramVo detailV1(ProgramGetDto programGetDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(),programGetDto);
        return getDetail(programGetDto);
    }
    
    /**
     * 查询节目详情V2
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public ProgramVo detailV2(ProgramGetDto programGetDto) {
        compositeContainer.execute(CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue(),programGetDto);
        return getDetailV2(programGetDto);
    }
    
    /**
     * 查询节目详情执行
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public ProgramVo getDetail(ProgramGetDto programGetDto) {
        ProgramShowTime programShowTime = programShowTimeService.selectProgramShowTimeByProgramId(programGetDto.getId());
        ProgramVo programVo = programService.getById(programGetDto.getId(),DateUtils.countBetweenSecond(DateUtils.now(),
                programShowTime.getShowTime()), TimeUnit.SECONDS);
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());
        
        ProgramGroupVo programGroupVo = programService.getProgramGroup(programVo.getProgramGroupId());
        programVo.setProgramGroupVo(programGroupVo);
        
        preloadTicketUserList(programVo.getHighHeat());
        
        preloadAccountOrderCount(programVo.getId());
        
        ProgramCategory programCategory = getProgramCategory(programVo.getProgramCategoryId());
        if (Objects.nonNull(programCategory)) {
            programVo.setProgramCategoryName(programCategory.getName());
        }
        ProgramCategory parentProgramCategory = getProgramCategory(programVo.getParentProgramCategoryId());
        if (Objects.nonNull(parentProgramCategory)) {
            programVo.setParentProgramCategoryName(parentProgramCategory.getName());
        }
        
        List<TicketCategoryVo> ticketCategoryVoList =
                ticketCategoryService.selectTicketCategoryListByProgramId(programVo.getId(),
                        DateUtils.countBetweenSecond(DateUtils.now(),programShowTime.getShowTime()), TimeUnit.SECONDS);
        programVo.setTicketCategoryVoList(ticketCategoryVoList);
        
        return programVo;
    }
    
    /**
     * 查询节目详情V2执行
     * @param programGetDto 查询节目数据的入参
     * @return 执行后的结果
     * */
    public ProgramVo getDetailV2(ProgramGetDto programGetDto) {
        ProgramShowTime programShowTime =
                programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(programGetDto.getId());
        
        ProgramVo programVo = programService.getByIdMultipleCache(programGetDto.getId(),programShowTime.getShowTime());
        
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());
        
        ProgramGroupVo programGroupVo = programService.getProgramGroupMultipleCache(programVo.getProgramGroupId());
        programVo.setProgramGroupVo(programGroupVo);
        
        preloadTicketUserList(programVo.getHighHeat());
        
        preloadAccountOrderCount(programVo.getId());
        
        ProgramCategory programCategory = getProgramCategoryMultipleCache(programVo.getProgramCategoryId());
        if (Objects.nonNull(programCategory)) {
            programVo.setProgramCategoryName(programCategory.getName());
        }
        ProgramCategory parentProgramCategory = getProgramCategoryMultipleCache(programVo.getParentProgramCategoryId());
        if (Objects.nonNull(parentProgramCategory)) {
            programVo.setParentProgramCategoryName(parentProgramCategory.getName());
        }
        
        List<TicketCategoryVo> ticketCategoryVoList = ticketCategoryService
                .selectTicketCategoryListByProgramIdMultipleCache(programVo.getId(),programShowTime.getShowTime());
        programVo.setTicketCategoryVoList(ticketCategoryVoList);
        
        return programVo;
    }
    
    /**
     * 查询节目表详情执行（多级）
     * @param programId 节目id
     * @param showTime 节目演出时间
     * @return 执行后的结果
     * */
    public ProgramVo getByIdMultipleCache(Long programId, Date showTime){
        return localCacheProgram.getCache(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId).getRelKey(),
                key -> {
                    log.info("查询节目详情 从本地缓存没有查询到 节目id : {}",programId);
                    ProgramVo programVo = getById(programId,DateUtils.countBetweenSecond(DateUtils.now(),showTime),
                            TimeUnit.SECONDS);
                    programVo.setShowTime(showTime);
                    return programVo;
                });
    }
    
    public ProgramVo simpleGetByIdMultipleCache(Long programId){
        ProgramVo programVoCache = localCacheProgram.getCache(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, 
                programId).getRelKey());
        if (Objects.nonNull(programVoCache)) {
            return programVoCache;
        }
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId), ProgramVo.class);
    }
    
    public ProgramVo simpleGetProgramAndShowMultipleCache(Long programId){
        ProgramShowTime programShowTime =
                programShowTimeService.simpleSelectProgramShowTimeByProgramIdMultipleCache(programId);
        if (Objects.isNull(programShowTime)) {
            throw new StellarisFrameException(BaseCode.PROGRAM_SHOW_TIME_NOT_EXIST);
        }
        
        ProgramVo programVo = simpleGetByIdMultipleCache(programId);
        if (Objects.isNull(programVo)) {
            throw new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
        
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());
        
        return programVo;
    }
    
    @ServiceLock(lockType= LockType.Read,name = PROGRAM_LOCK,keys = {"#programId"})
    public ProgramVo getById(Long programId,Long expireTime,TimeUnit timeUnit) {
        ProgramVo programVo = 
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId), ProgramVo.class);
        if (Objects.nonNull(programVo)) {
            return programVo;
        }
        log.info("查询节目详情 从Redis缓存没有查询到 节目id : {}",programId);
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_LOCK, new String[]{String.valueOf(programId)});
        lock.lock();
        try {
            return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM,programId)
                    ,ProgramVo.class,
                    () -> createProgramVo(programId)
                    ,expireTime,
                    timeUnit);
        }finally {
            lock.unlock();
        }
    }
    
    public ProgramGroupVo getProgramGroupMultipleCache(Long programGroupId){
        return localCacheProgramGroup.getCache(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId).getRelKey(),
                key -> getProgramGroup(programGroupId));
    }
    @ServiceLock(lockType= LockType.Read,name = PROGRAM_GROUP_LOCK,keys = {"#programGroupId"})
    public ProgramGroupVo getProgramGroup(Long programGroupId) {
        ProgramGroupVo programGroupVo =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId), ProgramGroupVo.class);
        if (Objects.nonNull(programGroupVo)) {
            return programGroupVo;
        }
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_PROGRAM_LOCK, new String[]{String.valueOf(programGroupId)});
        lock.lock();
        try {
            programGroupVo = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId), 
                    ProgramGroupVo.class);
            if (Objects.isNull(programGroupVo)) {
                programGroupVo = createProgramGroupVo(programGroupId);
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programGroupId),programGroupVo,
                        DateUtils.countBetweenSecond(DateUtils.now(),programGroupVo.getRecentShowTime()),TimeUnit.SECONDS);
            }
            return programGroupVo;
        }finally {
            lock.unlock();
        }
    }
    
    public Map<Long, String> selectProgramCategoryMap(Collection<Long> programCategoryIdList){
        LambdaQueryWrapper<ProgramCategory> pcLambdaQueryWrapper = Wrappers.lambdaQuery(ProgramCategory.class)
                .in(ProgramCategory::getId, programCategoryIdList);
        List<ProgramCategory> programCategoryList = programCategoryMapper.selectList(pcLambdaQueryWrapper);
        return programCategoryList
                .stream()
                .collect(Collectors.toMap(ProgramCategory::getId, ProgramCategory::getName, (v1, v2) -> v2));
    }
    
    public Map<Long, TicketCategoryAggregate> selectTicketCategorieMap(List<Long> programIdList){
        List<TicketCategoryAggregate> ticketCategorieList = ticketCategoryMapper.selectAggregateList(programIdList);
        return ticketCategorieList
                .stream()
                .collect(Collectors.toMap(TicketCategoryAggregate::getProgramId, 
                        ticketCategory -> ticketCategory, (v1, v2) -> v2));
    }
    
    @RepeatExecuteLimit(name = REDUCE_REMAIN_NUMBER,keys = {"#reduceRemainNumberDto.programId","#reduceRemainNumberDto.seatIdList"})
    @Transactional(rollbackFor = Exception.class)
    public Boolean operateSeatLockAndTicketCategoryRemainNumber(ReduceRemainNumberDto reduceRemainNumberDto){
        OrderInventoryOperation existingOperation = orderInventoryOperationMapper.selectOne(
                Wrappers.lambdaQuery(OrderInventoryOperation.class)
                        .eq(OrderInventoryOperation::getProgramId, reduceRemainNumberDto.getProgramId())
                        .eq(OrderInventoryOperation::getOrderNumber, reduceRemainNumberDto.getOrderNumber()));
        if (Objects.nonNull(existingOperation)) {
            // Feign 响应丢失、Kafka 重投等场景直接返回成功，不能重复锁座和扣减余票。
            if (reduceRemainNumberDto.getEventId() != null && existingOperation.getEventId() != null
                    && !Objects.equals(existingOperation.getEventId(), reduceRemainNumberDto.getEventId())) {
                throw new StellarisFrameException(BaseCode.PARAMETER_ERROR);
            }
            return true;
        }
        // 只拒绝“首次”落库的过期 reservation。若库存操作已经提交，重试必须继续补建订单。
        if (reduceRemainNumberDto.getIntentId() != null && !reduceRemainNumberDto.getIntentId().isBlank()
                && (reduceRemainNumberDto.getReservationExpireTime() == null
                || !reduceRemainNumberDto.getReservationExpireTime().after(DateUtils.now()))) {
            throw new StellarisFrameException(BaseCode.SEAT_SOLD);
        }
        List<TicketCategoryCountDto> ticketCategoryCountDtoList = reduceRemainNumberDto.getTicketCategoryCountDtoList();
        List<Long> seatIdList = reduceRemainNumberDto.getSeatIdList();
        if (seatIdList == null || seatIdList.isEmpty()
                || seatIdList.stream().distinct().count() != seatIdList.size()) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR);
        }
        LambdaQueryWrapper<Seat> seatLambdaQueryWrapper = 
                Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId,reduceRemainNumberDto.getProgramId())
                        .in(Seat::getId, seatIdList);
        //查询座位，进行相关验证
        List<Seat> seatList = seatMapper.selectList(seatLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(seatList)) {
            throw new StellarisFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        if (seatList.size() != seatIdList.size()) {
            throw new StellarisFrameException(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT);
        }
        for (Seat seat : seatList) {
            if (!Objects.equals(seat.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
                throw new StellarisFrameException(BaseCode.SEAT_IS_NOT_NOT_SOLD);
            }
        }
        //修改座位状态
        LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper = 
                Wrappers.lambdaUpdate(Seat.class)
                        .eq(Seat::getProgramId,reduceRemainNumberDto.getProgramId())
                        .in(Seat::getId, seatIdList)
                        .eq(Seat::getSellStatus, SellStatus.NO_SOLD.getCode())
                        .set(Seat::getSellStatus, reduceRemainNumberDto.getSellStatus())
                        .setSql("seat_version = COALESCE(seat_version, 0) + 1");
        if (reduceRemainNumberDto.getIntentId() != null && !reduceRemainNumberDto.getIntentId().isBlank()) {
            seatLambdaUpdateWrapper.isNull(Seat::getReservationId)
                    .set(Seat::getReservationId, reduceRemainNumberDto.getIntentId());
        }
        int updatedSeats = seatMapper.update(null,seatLambdaUpdateWrapper);
        if (updatedSeats != seatIdList.size()) {
            throw new StellarisFrameException(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT);
        }
        
        int updateRemainNumberCount = 0;
        for (TicketCategoryCountDto ticketCategoryCountDto : ticketCategoryCountDtoList) {
            //修改余票
            updateRemainNumberCount = updateRemainNumberCount + ticketCategoryMapper.reduceRemainNumber(
                    ticketCategoryCountDto.getCount(), ticketCategoryCountDto.getTicketCategoryId(),
                    reduceRemainNumberDto.getProgramId());
        }
        if (updateRemainNumberCount != ticketCategoryCountDtoList.size()) {
            throw new StellarisFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
        }

        // 与座位、票档库存处于同一本地事务；事务提交后即使响应丢失，重试也能识别为已处理。
        Date now = DateUtils.now();
        OrderInventoryOperation operation = new OrderInventoryOperation();
        operation.setId(uidGenerator.getUid());
        operation.setOrderNumber(reduceRemainNumberDto.getOrderNumber());
        operation.setEventId(reduceRemainNumberDto.getEventId());
        operation.setProgramId(reduceRemainNumberDto.getProgramId());
        operation.setCreateTime(now);
        operation.setEditTime(now);
        operation.setStatus(1);
        orderInventoryOperationMapper.insert(operation);
        return true;
    }

    /**
     * 到期清理的保守保护：只要节目库已经出现该 reservation 的 LOCK 座位，
     * 清理任务就不能仅凭订单服务暂时查不到订单而释放 Redis 预订。
     */
    public boolean hasLockedReservation(Long programId, String reservationId) {
        if (programId == null || reservationId == null || reservationId.isBlank()) {
            return false;
        }
        Long count = seatMapper.selectCount(Wrappers.lambdaQuery(Seat.class)
                .eq(Seat::getProgramId, programId)
                .eq(Seat::getReservationId, reservationId)
                .eq(Seat::getSellStatus, SellStatus.LOCK.getCode()));
        return count != null && count > 0;
    }
    
    @RepeatExecuteLimit(name = PAY_OR_CANCEL_PROGRAM_ORDER,keys = {"#programOperateDataDto.programId","#programOperateDataDto.seatIdList"})
    @Transactional(rollbackFor = Exception.class)
    public Boolean operateProgramData(ProgramOperateDataDto programOperateDataDto){
        List<Long> seatIdList = programOperateDataDto.getSeatIdList();
        if (seatIdList == null || seatIdList.isEmpty()
                || seatIdList.stream().distinct().count() != seatIdList.size()) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR);
        }
        LambdaQueryWrapper<Seat> seatLambdaQueryWrapper =
                Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId,programOperateDataDto.getProgramId())
                        .in(Seat::getId, seatIdList);
        List<Seat> seatList = seatMapper.selectList(seatLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(seatList)) {
            throw new StellarisFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        if (seatList.size() != seatIdList.size()) {
            throw new StellarisFrameException(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT);
        }
        //座位的操作状态只能是售卖或者未售卖
        if (!Objects.equals(programOperateDataDto.getSellStatus(),SellStatus.SOLD.getCode()) && 
                !Objects.equals(programOperateDataDto.getSellStatus(),SellStatus.NO_SOLD.getCode())) {
            throw new StellarisFrameException(BaseCode.SEAT_OPERATE_IS_NOT_NOT_SOLD_OR_SOLD);
        }
        Integer orderVersion = programOperateDataDto.getOrderVersion();
        boolean v5Reservation = Objects.equals(orderVersion, ProgramOrderVersion.V5_REFERENCE.getValue());
        boolean reservationModel = Objects.equals(orderVersion, ProgramOrderVersion.V4_VERSION.getValue())
                || v5Reservation;
        if (v5Reservation && (programOperateDataDto.getIntentId() == null
                || programOperateDataDto.getIntentId().isBlank())) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR);
        }
        // v1/v2/v3 是历史直售模型；v4/v5 都要求订单创建时座位已经处于 LOCK。
        if (!reservationModel) {
            for (Seat seat : seatList) {
                if (Objects.equals(seat.getSellStatus(), SellStatus.SOLD.getCode())) {
                    throw new StellarisFrameException(BaseCode.SEAT_SOLD);
                }
            }
            LambdaUpdateWrapper<Seat> seatLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(Seat.class)
                            .eq(Seat::getProgramId,programOperateDataDto.getProgramId())
                            .in(Seat::getId, seatIdList);
            Seat updateSeat = new Seat();
            updateSeat.setSellStatus(SellStatus.SOLD.getCode());
            seatMapper.update(updateSeat,seatLambdaUpdateWrapper);
            List<TicketCategoryCountDto> ticketCategoryCountDtoList = programOperateDataDto.getTicketCategoryCountDtoList();
            int updateRemainNumberCount =
                    ticketCategoryMapper.batchUpdateRemainNumber(ticketCategoryCountDtoList,programOperateDataDto.getProgramId());
            if (updateRemainNumberCount != ticketCategoryCountDtoList.size()) {
                throw new StellarisFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
            }
        }else {
            boolean releasing = Objects.equals(programOperateDataDto.getSellStatus(), SellStatus.NO_SOLD.getCode());
            boolean allAtTarget = seatList.stream().allMatch(seat ->
                    Objects.equals(seat.getSellStatus(), programOperateDataDto.getSellStatus())
                            && (!v5Reservation || releasing && seat.getReservationId() == null
                            || !releasing && Objects.equals(seat.getReservationId(), programOperateDataDto.getIntentId())));
            if (allAtTarget) {
                return true;
            }
            // 首次执行只能由 LOCK 迁移；最终状态重复调用在上方直接幂等返回。
            for (Seat seat : seatList) {
                if (!Objects.equals(seat.getSellStatus(), SellStatus.LOCK.getCode())
                        || v5Reservation && !Objects.equals(seat.getReservationId(), programOperateDataDto.getIntentId())) {
                    throw new StellarisFrameException(BaseCode.SEAT_IS_NOT_NOT_LOCK);
                }
            }
            LambdaUpdateWrapper<Seat> terminalUpdate = Wrappers.lambdaUpdate(Seat.class)
                    .eq(Seat::getProgramId, programOperateDataDto.getProgramId())
                    .in(Seat::getId, seatIdList)
                    .eq(Seat::getSellStatus, SellStatus.LOCK.getCode())
                    .set(Seat::getSellStatus, programOperateDataDto.getSellStatus())
                    .setSql("seat_version = COALESCE(seat_version, 0) + 1");
            if (v5Reservation) {
                terminalUpdate.eq(Seat::getReservationId, programOperateDataDto.getIntentId());
                if (releasing) {
                    terminalUpdate.set(Seat::getReservationId, null);
                }
            }
            int terminalUpdated = seatMapper.update(null, terminalUpdate);
            if (terminalUpdated != seatIdList.size()) {
                throw new StellarisFrameException(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT);
            }
            if (releasing) {
                //订单取消的操作
                List<TicketCategoryCountDto> ticketCategoryCountDtoList = programOperateDataDto.getTicketCategoryCountDtoList();
                int updateRemainNumberCount = 0;
                //把库存增加回去
                for (TicketCategoryCountDto ticketCategoryCountDto : ticketCategoryCountDtoList) {
                    updateRemainNumberCount = updateRemainNumberCount + ticketCategoryMapper.increaseRemainNumber(
                            ticketCategoryCountDto.getCount(), ticketCategoryCountDto.getTicketCategoryId(),
                            programOperateDataDto.getProgramId());
                }
                if (updateRemainNumberCount != ticketCategoryCountDtoList.size()) {
                    throw new StellarisFrameException(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT);
                }
            }
        }
        return true;
    }
    
    private ProgramVo createProgramVo(Long programId){
        ProgramVo programVo = new ProgramVo();
        Program program = 
                Optional.ofNullable(programMapper.selectById(programId))
                        .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST));
        BeanUtil.copyProperties(program,programVo);
        AreaGetDto areaGetDto = new AreaGetDto();
        areaGetDto.setId(program.getAreaId());
        ApiResponse<AreaVo> areaResponse = baseDataClient.getById(areaGetDto);
        if (Objects.equals(areaResponse.getCode(), ApiResponse.ok().getCode())) {
            if (Objects.nonNull(areaResponse.getData())) {
                programVo.setAreaName(areaResponse.getData().getName());
            }
        }else {
            log.error("base-data rpc getById error areaResponse:{}", JSON.toJSONString(areaResponse));
        }
        return programVo;
    }
    
    private ProgramGroupVo createProgramGroupVo(Long programGroupId){
        ProgramGroupVo programGroupVo = new ProgramGroupVo();
        ProgramGroup programGroup =
                Optional.ofNullable(programGroupMapper.selectById(programGroupId))
                        .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_GROUP_NOT_EXIST));
        programGroupVo.setId(programGroup.getId());
        programGroupVo.setProgramSimpleInfoVoList(JSON.parseArray(programGroup.getProgramJson(), ProgramSimpleInfoVo.class));
        programGroupVo.setRecentShowTime(programGroup.getRecentShowTime());
        return programGroupVo;
    }
    
    public List<Long> getAllProgramIdList(){
        LambdaQueryWrapper<Program> programLambdaQueryWrapper =
                Wrappers.lambdaQuery(Program.class).eq(Program::getProgramStatus, BusinessStatus.YES.getCode())
                        .select(Program::getId);
        List<Program> programs = programMapper.selectList(programLambdaQueryWrapper);
        return programs.stream().map(Program::getId).collect(Collectors.toList());
    }
    
    public ProgramVo getDetailFromDb(Long programId) {
        ProgramVo programVo = createProgramVo(programId);
        
        ProgramCategory programCategory = getProgramCategory(programVo.getProgramCategoryId());
        if (Objects.nonNull(programCategory)) {
            programVo.setProgramCategoryName(programCategory.getName());
        }
        ProgramCategory parentProgramCategory = getProgramCategory(programVo.getParentProgramCategoryId());
        if (Objects.nonNull(parentProgramCategory)) {
            programVo.setParentProgramCategoryName(parentProgramCategory.getName());
        }
        
        LambdaQueryWrapper<ProgramShowTime> programShowTimeLambdaQueryWrapper =
                Wrappers.lambdaQuery(ProgramShowTime.class).eq(ProgramShowTime::getProgramId, programId);
        ProgramShowTime programShowTime = Optional.ofNullable(programShowTimeMapper.selectOne(programShowTimeLambdaQueryWrapper))
                .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_SHOW_TIME_NOT_EXIST));
        
        programVo.setShowTime(programShowTime.getShowTime());
        programVo.setShowDayTime(programShowTime.getShowDayTime());
        programVo.setShowWeekTime(programShowTime.getShowWeekTime());
        
        return programVo;
    }
    
    private void preloadTicketUserList(Integer highHeat){
        if (Objects.equals(highHeat, BusinessStatus.NO.getCode())) {
            return;
        }
        String userId = BaseParameterHolder.getParameter(USER_ID);
        String code = BaseParameterHolder.getParameter(CODE);
        if (StringUtil.isEmpty(userId) || StringUtil.isEmpty(code)) {
            return;
        }
        Boolean userLogin =
                redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, code, userId));
        if (!userLogin) {
            return;
        }
        BusinessThreadPool.execute(() -> {
            try {
                if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_USER_LIST,userId))) {
                    TicketUserListDto ticketUserListDto = new TicketUserListDto();
                    ticketUserListDto.setUserId(Long.parseLong(userId));
                    ApiResponse<List<TicketUserVo>> apiResponse = userClient.list(ticketUserListDto);
                    if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                        Optional.ofNullable(apiResponse.getData()).filter(CollectionUtil::isNotEmpty)
                                .ifPresent(ticketUserVoList -> redisCache.set(RedisKeyBuild.createRedisKey(
                                        RedisKeyManage.TICKET_USER_LIST,userId),ticketUserVoList));
                    }else {
                        log.warn("userClient.select 调用失败 apiResponse : {}",JSON.toJSONString(apiResponse));
                    }
                }
                
            }catch (Exception e) {
                log.error("预热加载购票人列表失败",e);
            }
        });
    }
    
    private void preloadAccountOrderCount(Long programId){
        String userId = BaseParameterHolder.getParameter(USER_ID);
        String code = BaseParameterHolder.getParameter(CODE);
        if (StringUtil.isEmpty(userId) || StringUtil.isEmpty(code)) {
            return;
        }
        Boolean userLogin =
                redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, code, userId));
        if (!userLogin) {
            return;
        }
        BusinessThreadPool.execute(() -> {
            try {
                if (!redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,userId,programId))) {
                    AccountOrderCountDto accountOrderCountDto = new AccountOrderCountDto();
                    accountOrderCountDto.setUserId(Long.parseLong(userId));
                    accountOrderCountDto.setProgramId(programId);
                    ApiResponse<AccountOrderCountVo> apiResponse = orderClient.accountOrderCount(accountOrderCountDto);
                    if (Objects.equals(apiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                        Optional.ofNullable(apiResponse.getData())
                                .ifPresent(accountOrderCountVo -> redisCache.set(
                                        RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,userId,programId),
                                        accountOrderCountVo.getCount(), tokenExpireManager.getTokenExpireTime() + 1,
                                        TimeUnit.MINUTES));
                    }else {
                        log.warn("orderClient.accountOrderCount 调用失败 apiResponse : {}",JSON.toJSONString(apiResponse));
                    }
                }
            }catch (Exception e) {
                log.error("预热加载账户订单数量失败",e);
            }
        });
    }
    
    public ProgramCategory getProgramCategoryMultipleCache(Long programCategoryId){
        return localCacheProgramCategory.get(String.valueOf(programCategoryId),
                key -> getProgramCategory(programCategoryId));
    }
    
    public ProgramCategory getProgramCategory(Long programCategoryId){
        return programCategoryService.getProgramCategory(programCategoryId);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Boolean resetExecute(ProgramResetExecuteDto programResetExecuteDto) {
        if (!demoResetEnabled) {
            throw new IllegalStateException("Demo inventory reset is disabled; set program.demo-reset-enabled=true explicitly");
        }
        Long programId = programResetExecuteDto.getProgramId();
        //查出该节目下锁定和已售卖的座位
        LambdaQueryWrapper<Seat> seatQueryWrapper =
                Wrappers.lambdaQuery(Seat.class).eq(Seat::getProgramId, programId)
                        .in(Seat::getSellStatus,SellStatus.LOCK.getCode(),SellStatus.SOLD.getCode());
        List<Seat> seatList = seatMapper.selectList(seatQueryWrapper);
        if (CollectionUtil.isNotEmpty(seatList)) {
            //执行到这里说明有锁定和已售卖的座位，那么就把该节目下的座位都重置一遍
            LambdaUpdateWrapper<Seat> seatUpdateWrapper =
                    Wrappers.lambdaUpdate(Seat.class).eq(Seat::getProgramId, programId);
            Seat seatUpdate = new Seat();
            seatUpdate.setSellStatus(SellStatus.NO_SOLD.getCode());
            seatMapper.update(seatUpdate,seatUpdateWrapper);
        }
        //查询该节目下的票档
        LambdaQueryWrapper<TicketCategory> ticketCategoryQueryWrapper =
                Wrappers.lambdaQuery(TicketCategory.class).eq(TicketCategory::getProgramId, programId);
        List<TicketCategory> ticketCategories = ticketCategoryMapper.selectList(ticketCategoryQueryWrapper);
        if (CollectionUtil.isNotEmpty(ticketCategories)) {
            for (TicketCategory ticketCategory : ticketCategories) {
                Long remainNumber = ticketCategory.getRemainNumber();
                Long totalNumber = ticketCategory.getTotalNumber();
                //如果总数和剩余数不一致，则进行重置
                if (!(remainNumber.equals(totalNumber))) {
                    TicketCategory ticketCategoryUpdate = new TicketCategory();
                    ticketCategoryUpdate.setRemainNumber(totalNumber);
                    
                    LambdaUpdateWrapper<TicketCategory> ticketCategoryUpdateWrapper =
                            Wrappers.lambdaUpdate(TicketCategory.class)
                                    .eq(TicketCategory::getProgramId, programId)
                                    .eq(TicketCategory::getId,ticketCategory.getId());
                    ticketCategoryMapper.update(ticketCategoryUpdate,ticketCategoryUpdateWrapper);
                }
            }
        }
        //删除缓存相关数据
        delRedisData(programId);
        //删除本地缓存数据
        delLocalCache(programId);
        return true;
    }
    
    public void delRedisData(Long programId){
        Program program = Optional.ofNullable(programMapper.selectById(programId))
                .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST));
        List<RedisKeyBuild> keys = new ArrayList<>();
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM,programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP,program.getProgramGroupId()));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME,programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId));
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, programId));
        List<TicketCategory> categories = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programId));
        for (TicketCategory category : categories) {
            keys.add(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, category.getId()));
            keys.add(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, category.getId()));
            keys.add(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, category.getId()));
            keys.add(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, category.getId()));
        }
        redisCache.del(keys);
    }
    
    public Boolean invalid(final ProgramInvalidDto programInvalidDto) {
        Program program = new Program();
        program.setId(programInvalidDto.getId());
        program.setProgramStatus(BusinessStatus.NO.getCode());
        int result = programMapper.updateById(program);
        if (result > 0) {
            delRedisData(programInvalidDto.getId());
            redisStreamPushHandler.push(String.valueOf(programInvalidDto.getId()));
            programEs.deleteByProgramId(programInvalidDto.getId());
            return true;
        }else {
            return false;
        }
    }
    
    public ProgramVo localDetail(final ProgramGetDto programGetDto) {
        return localCacheProgram.getCache(String.valueOf(programGetDto.getId()));
    }
    
    public void delLocalCache(Long programId){
        log.info("删除本地缓存 programId : {}",programId);
        localCacheProgram.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId).getRelKey());
        localCacheProgramGroup.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, programId).getRelKey());
        localCacheProgramShowTime.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId).getRelKey());
        localCacheTicketCategory.del(programId);
    }
    
    @ServiceLock(name = REFERENCE_PREHEAT, keys = {"#programDataPreheatDto.programId"})
    public Boolean dataPreheat(ProgramDataPreheatDto programDataPreheatDto){
        long programId = programDataPreheatDto.getProgramId();
        // 冷启动时可能有多个请求同时发现缓存缺失。节目锁内二次检查，只有首个请求读取一次 MySQL 快照，
        // 后续请求以及正常热路径均直接使用 Redis，避免重复、破坏性重建。
        if (referenceSeatInventoryService.isReady(programId)) {
            bloomFilterHandler.add(String.valueOf(programId));
            return true;
        }
        // 原子关闸并拒绝活跃锁座；预热只重建缓存，不修改数据库业务状态。
        referenceSeatInventoryService.beginPreheat(programId);
        boolean completed = false;
        try {
            Program program = Optional.ofNullable(programMapper.selectById(programId))
                    .orElseThrow(() -> new StellarisFrameException(BaseCode.PROGRAM_NOT_EXIST));
            if (!Objects.equals(program.getProgramStatus(), BusinessStatus.YES.getCode())) {
                throw new IllegalStateException("Only an active program can be preheated");
            }
            List<TicketCategory> categories = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                    .eq(TicketCategory::getProgramId, programId));
            List<Seat> seats = seatMapper.selectList(Wrappers.lambdaQuery(Seat.class)
                    .eq(Seat::getProgramId, programId));
            if (CollectionUtil.isEmpty(categories) || CollectionUtil.isEmpty(seats)) {
                throw new IllegalStateException("Program ticket categories and full seat snapshot are required");
            }
            if (seats.stream().anyMatch(seat -> Objects.equals(seat.getSellStatus(), SellStatus.LOCK.getCode()))) {
                throw new IllegalStateException("Locked seats exist in database; reconcile them before preheat");
            }
            Map<Long, Long> availableByCategory = seats.stream()
                    .filter(seat -> Objects.equals(seat.getSellStatus(), SellStatus.NO_SOLD.getCode()))
                    .collect(Collectors.groupingBy(Seat::getTicketCategoryId, Collectors.counting()));
            for (TicketCategory category : categories) {
                long actualAvailable = availableByCategory.getOrDefault(category.getId(), 0L);
                if (!Objects.equals(category.getRemainNumber(), actualAvailable)) {
                    throw new IllegalStateException("Database remain number does not match available seats, categoryId="
                            + category.getId());
                }
            }

            // 精确删除本节目缓存键，避免 Cluster 环境下通过 Lua KEYS(pattern) 跨槽扫描。
            List<RedisKeyBuild> cacheKeys = new ArrayList<>();
            cacheKeys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId));
            cacheKeys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_GROUP, program.getProgramGroupId()));
            cacheKeys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SHOW_TIME, programId));
            cacheKeys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST, programId));
            for (TicketCategory category : categories) {
                cacheKeys.add(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, category.getId()));
                cacheKeys.add(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, category.getId()));
                cacheKeys.add(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, category.getId()));
                cacheKeys.add(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, category.getId()));
            }
            redisCache.del(cacheKeys);
            delLocalCache(programId);

            List<SeatVo> referenceSeats = BeanUtil.copyToList(seats, SeatVo.class);
            referenceSeatInventoryService.bootstrapFromSnapshot(programId, referenceSeats);

            // v5 ready 发布后再回填展示/兼容链路缓存；这些缓存不是 v5 锁座的业务真相。
            // 动态新增节目必须先登记到布隆过滤器，否则详情链路会把合法节目误判为不存在。
            bloomFilterHandler.add(String.valueOf(programId));
            ProgramGetDto programGetDto = new ProgramGetDto();
            programGetDto.setId(programId);
            ProgramVo programVo = getDetailV2(programGetDto);
            Date showDayTime = programVo.getShowDayTime();
            for (TicketCategory category : categories) {
                seatService.selectSeatResolution(programId, category.getId(),
                        DateUtils.countBetweenSecond(DateUtils.now(), showDayTime), TimeUnit.SECONDS);
                ticketCategoryService.getRedisRemainNumberResolution(programId, category.getId());
            }
            completed = true;
            return true;
        } finally {
            if (!completed) {
                referenceSeatInventoryService.abortPreheat(programId);
            }
        }
    }
}
