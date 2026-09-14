package com.stellaris.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.client.OrderClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramManageDto;
import com.stellaris.dto.SeatInventoryCountDto;
import com.stellaris.dto.SeatInventoryQueryDto;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.dto.SeatPageManageDto;
import com.stellaris.entity.Seat;
import com.stellaris.entity.TicketCategory;
import com.stellaris.enums.SellStatus;
import com.stellaris.enums.BaseCode;
import com.stellaris.mapper.SeatMapper;
import com.stellaris.mapper.TicketCategoryMapper;
import com.stellaris.page.PageUtil;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.reference.SeatReservationKeys;
import com.stellaris.service.reference.ReferenceSeatInventoryService;
import com.stellaris.vo.SeatVo;
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
 * @author: xz_y
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

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private ReferenceSeatInventoryService referenceSeatInventoryService;
    
    
    public List<TicketCategoryDetailManageVo> ticketCategoryList(ProgramManageDto programManageDto) {
        List<TicketCategory> ticketCategorieList = ticketCategoryMapper.selectList(Wrappers.lambdaQuery(TicketCategory.class)
                .eq(TicketCategory::getProgramId, programManageDto.getProgramId())
                .orderByAsc(TicketCategory::getPrice));
        return ticketCategorieList.stream().map(ticketCategory -> {
            TicketCategoryDetailManageVo ticketCategoryDetailManageVo = new TicketCategoryDetailManageVo();
            BeanUtil.copyProperties(ticketCategory,ticketCategoryDetailManageVo);
            ticketCategoryDetailManageVo.setDbRemainNumber(tradeAvailableCount(
                    ticketCategory.getProgramId(), ticketCategory.getId()));
            Number redisCount = (Number) redisCache.getInstance().opsForZSet()
                    .size(SeatReservationKeys.available(ticketCategory.getProgramId(), ticketCategory.getId()));
            ticketCategoryDetailManageVo.setRedisRemainNumber(redisCount == null ? 0L : redisCount.longValue());
            return ticketCategoryDetailManageVo;
        }).collect(Collectors.toList());
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
        SeatInventoryQueryDto query = new SeatInventoryQueryDto();
        query.setProgramId(seatPageManageDto.getProgramId());
        ApiResponse<List<SeatInventorySnapshotDto>> tradeResponse = orderClient.currentSeatInventory(query);
        if (tradeResponse == null || !Objects.equals(tradeResponse.getCode(), BaseCode.SUCCESS.getCode())
                || tradeResponse.getData() == null) {
            throw new IllegalStateException("trade inventory is unavailable");
        }
        Map<Long, SeatInventorySnapshotDto> tradeSeatMap = tradeResponse.getData().stream()
                .collect(Collectors.toMap(SeatInventorySnapshotDto::getSeatId, item -> item));
        Map<Long, SeatVo> redisSeatMap = referenceSeatInventoryService.findCurrentByIds(
                        seatPageManageDto.getProgramId(),
                        seatPage.getRecords().stream().map(Seat::getId).toList()).stream()
                .collect(Collectors.toMap(SeatVo::getId, item -> item));
        
        List<SeatManageVo> seatManageVoList = new ArrayList<>();
        for (Seat seat : seatPage.getRecords()) {
            SeatManageVo seatManageVo = new SeatManageVo();
            BeanUtil.copyProperties(seat,seatManageVo);
            SeatInventorySnapshotDto tradeSeat = tradeSeatMap.get(seat.getId());
            if (tradeSeat == null) throw new IllegalStateException("trade inventory misses seat " + seat.getId());
            seatManageVo.setDbSellStatus(tradeSeat.getSellStatus());
            seatManageVo.setDbSellStatusName(SellStatus.getMsg(tradeSeat.getSellStatus()));
            SeatVo redisSeat = redisSeatMap.get(seat.getId());
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
