package com.stellaris.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.ProgramManageDto;
import com.stellaris.dto.SeatPageManageDto;
import com.stellaris.entity.Seat;
import com.stellaris.entity.TicketCategory;
import com.stellaris.enums.SellStatus;
import com.stellaris.mapper.SeatMapper;
import com.stellaris.mapper.TicketCategoryMapper;
import com.stellaris.page.PageUtil;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.SeatManageVo;
import com.stellaris.vo.TicketCategoryDbManageVo;
import com.stellaris.vo.TicketCategoryDetailManageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目后台管理 service
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class ProgramManageService  {
    
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    
    @Autowired
    private SeatMapper seatMapper;
    
    @Autowired
    private RedisCache redisCache;
    
    
    public List<TicketCategoryDetailManageVo> ticketCategoryList(ProgramManageDto programManageDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programManageDto.getProgramId())
                .orderByAsc(TicketCategory::getPrice));
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDetailManageVo ticketCategoryDetailManageVo = new TicketCategoryDetailManageVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailManageVo);
            ticketCategoryDetailManageVo.setDbRemainNumber(ticketCategory.getRemainNumber());
            //Key:票档id，value:节目id
            Map<String, Long> ticketCategoryRemainNumber =
                    redisCache.getAllMapForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,
                            ticketCategory.getProgramId(),ticketCategory.getId()), Long.class);
            if (CollectionUtil.isNotEmpty(ticketCategoryRemainNumber)) {
                ticketCategoryDetailManageVo.setRedisRemainNumber(ticketCategoryRemainNumber.get(ticketCategory.getId().toString()));
            }
            return ticketCategoryDetailManageVo;
        }).collect(Collectors.toList());
    }
    
    public List<TicketCategoryDbManageVo> dbTicketCategoryList(ProgramManageDto programManageDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programManageDto.getProgramId())
                .orderByAsc(TicketCategory::getPrice));
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDbManageVo ticketCategoryDbManageVo = new TicketCategoryDbManageVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDbManageVo);
            return ticketCategoryDbManageVo;
        }).toList();
    }
    
    public IPage<SeatManageVo> seatPage(SeatPageManageDto seatPageManageDto) {
        IPage<SeatManageVo> seatManageVoPage = new Page<>(seatPageManageDto.getPageNumber(), seatPageManageDto.getPageSize());
        //查询前5分钟订单节目管理表
        IPage<Seat> seatPage =
                seatMapper.selectPage(PageUtil.getPageParams(seatPageManageDto.getPageNumber(),
                        seatPageManageDto.getPageSize()),Wrappers.lambdaQuery(Seat.class)
                        .eq(Seat::getProgramId, seatPageManageDto.getProgramId())
                        .eq(Objects.nonNull(seatPageManageDto.getTicketCategoryId()),Seat::getTicketCategoryId, seatPageManageDto.getTicketCategoryId()));
        if (CollectionUtil.isEmpty(seatPage.getRecords())) {
            return seatManageVoPage;
        }
        //key:票档id，value:座位集合
        Map<Long, List<Seat>> seatMap = seatPage.getRecords().stream().collect(Collectors.groupingBy(Seat::getTicketCategoryId));
        //redis中座位数据 key:座位id，value:座位对象
        Map<Long,Seat> redisSeatMap = new HashMap<>(seatPage.getRecords().size());
        for (Entry<Long, List<Seat>> entry : seatMap.entrySet()) {
            Long ticketCategoryId = entry.getKey();
            List<String> seatIdList = entry.getValue().stream().map(Seat::getId).map(String::valueOf).toList();
            //从redis中批量查询未售卖的座位
            List<Seat> noSoldSeatList = redisCache.multiGetForHash(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, seatPageManageDto.getProgramId(), ticketCategoryId),seatIdList,Seat.class);
            //从redis中批量查询锁定中的座位
            List<Seat> lockSeatList = redisCache.multiGetForHash(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, seatPageManageDto.getProgramId(), ticketCategoryId),seatIdList,Seat.class);
            //从redis中批量查询未售卖的座位
            List<Seat> soldSeatList = redisCache.multiGetForHash(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, seatPageManageDto.getProgramId(), ticketCategoryId),seatIdList,Seat.class);
            for (Seat seat : noSoldSeatList) {
                redisSeatMap.put(seat.getId(),seat);
            }
            for (Seat seat : lockSeatList) {
                redisSeatMap.put(seat.getId(),seat);
            }
            for (Seat seat : soldSeatList) {
                redisSeatMap.put(seat.getId(),seat);
            }
        }
        
        List<SeatManageVo> seatManageVoList = new ArrayList<>();
        for (Seat seat : seatPage.getRecords()) {
            SeatManageVo seatManageVo = new SeatManageVo();
            BeanUtil.copyProperties(seat,seatManageVo);
            seatManageVo.setDbSellStatus(seat.getSellStatus());
            seatManageVo.setDbSellStatusName(SellStatus.getMsg(seat.getSellStatus()));
            Seat redisSeat = redisSeatMap.get(seat.getId());
            if (Objects.nonNull(redisSeat)) {
                seatManageVo.setRedisSellStatus(redisSeat.getSellStatus());
                seatManageVo.setRedisSellStatusName(Optional.ofNullable(SellStatus.getMsg(redisSeat.getSellStatus()))
                        .filter(StringUtil::isNotEmpty).orElse("无"));
            }
            seatManageVoList.add(seatManageVo);
        }
        BeanUtils.copyProperties(seatPage, seatManageVoPage);
        seatManageVoPage.setRecords(seatManageVoList);
        return seatManageVoPage;
    }
}
