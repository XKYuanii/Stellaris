package com.stellaris.service;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.domain.DiscardOrder;
import com.stellaris.domain.ProgramRecord;
import com.stellaris.domain.SeatRecord;
import com.stellaris.domain.TicketCategoryRecord;
import com.stellaris.dto.OrderPageManageDto;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.dto.RecordManageDto;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderProgram;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.entity.OrderTicketUserRecord;
import com.stellaris.enums.DiscardOrderReason;
import com.stellaris.enums.OrderStatus;
import com.stellaris.enums.ReconciliationStatus;
import com.stellaris.enums.RecordType;
import com.stellaris.enums.SellStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.mapper.OrderTicketUserRecordMapper;
import com.stellaris.page.PageUtil;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.util.DateUtils;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.DiscardOrderManageVo;
import com.stellaris.vo.DiscardOrderTicketUserManageVo;
import com.stellaris.vo.OrderManageVo;
import com.stellaris.vo.OrderTicketUserManageVo;
import com.stellaris.vo.RecordOrderManageVo;
import com.stellaris.vo.RecordOrderTickerUserManageVo;
import com.stellaris.vo.TicketCategoryVo;
import groovy.util.logging.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.stellaris.constant.Constant.GLIDE_LINE;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单后台管理 service
 * @author: xz_y
 **/
@Slf4j
@Service
public class OrderManageService {
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private OrderProgramMapper orderProgramMapper;
    
    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;
    
    @Autowired
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private OrderTaskService orderTaskService;
    
    
    public IPage<RecordOrderManageVo> recordPage(RecordManageDto recordManageDto) {
        IPage<RecordOrderManageVo> recordOrderManageVoPage = new Page<>(recordManageDto.getPageNumber(), recordManageDto.getPageSize());
        IPage<OrderProgram> orderProgramPage =
                orderProgramMapper.selectPage(PageUtil.getPageParams(recordManageDto.getPageNumber(),
                        recordManageDto.getPageSize()),Wrappers.lambdaQuery(OrderProgram.class)
                        .eq(OrderProgram::getProgramId, recordManageDto.getProgramId())
                        .le(OrderProgram::getCreateTime, DateUtils.addMinute(DateUtils.now(), -5)));
        
        if (CollectionUtil.isEmpty(orderProgramPage.getRecords())) {
            return recordOrderManageVoPage;
        }
        List<Long> orderNumberList = orderProgramPage.getRecords().stream().map(OrderProgram::getOrderNumber).toList();
        List<Order> orderList = orderMapper.selectList(Wrappers.lambdaQuery(Order.class).in(Order::getOrderNumber, orderNumberList));
        if (CollectionUtil.isEmpty(orderList)) {
            return recordOrderManageVoPage;
        }
        List<Long> identifierIdList = orderList.stream().map(Order::getIdentifierId).toList();
        
        List<OrderTicketUserRecord> allOrderTicketUserRecordList =
                orderTicketUserRecordMapper.selectList(Wrappers.lambdaQuery(OrderTicketUserRecord.class)
                        .in(OrderTicketUserRecord::getIdentifierId, identifierIdList));
        Map<Long, List<OrderTicketUserRecord>> allOrderTicketUserRecordMap =
                allOrderTicketUserRecordList.stream().collect(Collectors.groupingBy(OrderTicketUserRecord::getIdentifierId));
        
        List<TicketCategoryVo> ticketCategoryVoList =
                redisCache.getValueIsList(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_CATEGORY_LIST,
                        recordManageDto.getProgramId()), TicketCategoryVo.class);
        Map<Long, String> ticketCategoryVoMap = ticketCategoryVoList.stream().collect(Collectors.toMap(TicketCategoryVo::getId, TicketCategoryVo::getIntroduce));
        Map<String, String> redisProgramRecordMap = redisCache.getAllMapForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, recordManageDto.getProgramId()), String.class);
        redisProgramRecordMap.putAll(redisCache.getAllMapForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, recordManageDto.getProgramId()), String.class));
        
        List<RecordOrderManageVo> recordOrderManageVoList = new ArrayList<>();
        for (Order order : orderList) {
            RecordOrderManageVo recordOrderManageVo = new RecordOrderManageVo();
            recordOrderManageVo.setOrderNumber(order.getOrderNumber());
            recordOrderManageVo.setProgramId(order.getProgramId());
            recordOrderManageVo.setReconciliationStatus(order.getReconciliationStatus());
            recordOrderManageVo.setReconciliationStatusName(ReconciliationStatus.getMsg(order.getReconciliationStatus()));
            List<OrderTicketUserRecord> orderTicketUserRecordList = allOrderTicketUserRecordMap.get(order.getIdentifierId());
            if (CollectionUtil.isEmpty(orderTicketUserRecordList)) {
                continue;
            }
            List<RecordOrderTickerUserManageVo> recordOrderTickerUserManageVoList = new ArrayList<>();
            for (OrderTicketUserRecord orderTicketUserRecord : orderTicketUserRecordList) {
                RecordOrderTickerUserManageVo recordOrderTickerUserManageVo = new RecordOrderTickerUserManageVo();
                BeanUtils.copyProperties(orderTicketUserRecord, recordOrderTickerUserManageVo);
                recordOrderTickerUserManageVo.setReconciliationStatusName(ReconciliationStatus.getMsg(order.getReconciliationStatus()));
                recordOrderTickerUserManageVo.setDbRecordTypeCode(orderTicketUserRecord.getRecordTypeCode());
                recordOrderTickerUserManageVo.setDbRecordTypeValue(orderTicketUserRecord.getRecordTypeValue());
                recordOrderTickerUserManageVo.setDbRecordTypeName(RecordType.getMsg(orderTicketUserRecord.getRecordTypeCode()));
                recordOrderTickerUserManageVo.setTicketCategoryName(ticketCategoryVoMap.get(orderTicketUserRecord.getTicketCategoryId()));
                boolean redisRecordFlag = true;
                String redisProgramRecordStr = redisProgramRecordMap.get(orderTicketUserRecord.getRecordTypeValue() + GLIDE_LINE + orderTicketUserRecord.getIdentifierId() + GLIDE_LINE + orderTicketUserRecord.getUserId());
                if (StringUtil.isNotEmpty(redisProgramRecordStr)) {
                    ProgramRecord redisProgramRecord = JSON.parseObject(redisProgramRecordStr, ProgramRecord.class);
                    SeatRecord redisSeatRecord = getSeatRecord(redisProgramRecord, orderTicketUserRecord);
                    if (Objects.nonNull(redisSeatRecord)) {
                        recordOrderTickerUserManageVo.setRedisRecordTypeName(RecordType.getMsgByValue(redisProgramRecord.getRecordType()));
                        recordOrderTickerUserManageVo.setRedisBeforeSeatStatusName(SellStatus.getMsg(redisSeatRecord.getBeforeStatus()));
                        recordOrderTickerUserManageVo.setRedisAfterSeatStatusName(SellStatus.getMsg(redisSeatRecord.getAfterStatus()));
                    }
                }
                recordOrderTickerUserManageVoList.add(recordOrderTickerUserManageVo);
            }
            recordOrderManageVo.setRecordOrderTickerUserManageVoList(recordOrderTickerUserManageVoList);
            recordOrderManageVoList.add(recordOrderManageVo);
        }
        BeanUtils.copyProperties(orderProgramPage, recordOrderManageVoPage);
        recordOrderManageVoPage.setRecords(recordOrderManageVoList);
        return recordOrderManageVoPage;
    }
    
    
    public SeatRecord getSeatRecord(ProgramRecord programRecord,OrderTicketUserRecord orderTicketUserRecord){
        Map<Long, TicketCategoryRecord> redisTicketCategoryRecordMap = programRecord.getTicketCategoryRecordList().stream().collect(Collectors.toMap(TicketCategoryRecord::getTicketCategoryId, v -> v, (v1, v2) -> v2));
        TicketCategoryRecord redisTicketCategoryRecord = redisTicketCategoryRecordMap.get(orderTicketUserRecord.getTicketCategoryId());
        if (Objects.isNull(redisTicketCategoryRecord)) {
            return null;
        }
        Map<Long, SeatRecord> redisSeatRecordMap = redisTicketCategoryRecord.getSeatRecordList().stream().collect(Collectors.toMap(SeatRecord::getSeatId, v -> v, (v1, v2) -> v2));
        SeatRecord redisSeatRecord = redisSeatRecordMap.get(orderTicketUserRecord.getSeatId());
        if (Objects.isNull(redisSeatRecord)) {
            return null;
        }
        return redisSeatRecord;
    }
    
    public IPage<OrderManageVo> orderPage(OrderPageManageDto orderPageManageDto) {
        IPage<OrderManageVo> orderListManageVoPage = new Page<>(orderPageManageDto.getPageNumber(), orderPageManageDto.getPageSize());
        IPage<OrderProgram> orderProgramPage =
                orderProgramMapper.selectPage(PageUtil.getPageParams(orderPageManageDto.getPageNumber(),
                        orderPageManageDto.getPageSize()),Wrappers.lambdaQuery(OrderProgram.class)
                        .eq(OrderProgram::getProgramId, orderPageManageDto.getProgramId()));
        if (CollectionUtil.isEmpty(orderProgramPage.getRecords())) {
            return orderListManageVoPage;
        }
        List<Long> orderNumberList = orderProgramPage.getRecords().stream().map(OrderProgram::getOrderNumber).toList();
        List<Order> orderList = orderMapper.selectList(Wrappers.lambdaQuery(Order.class).in(Order::getOrderNumber, orderNumberList));
        if (CollectionUtil.isEmpty(orderList)) {
            return orderListManageVoPage;
        }
        List<OrderManageVo> orderManageVoList = new ArrayList<>();
        for (Order order : orderList) {
            OrderManageVo orderManageVo = new OrderManageVo();
            BeanUtils.copyProperties(order, orderManageVo);
            orderManageVo.setOrderStatusName(OrderStatus.getMsg(order.getOrderStatus()));
            List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(Wrappers.lambdaQuery(OrderTicketUser.class)
                    .eq(OrderTicketUser::getOrderNumber,order.getOrderNumber()));
            if (CollectionUtil.isNotEmpty(orderTicketUserList)) {
                List<OrderTicketUserManageVo> orderTicketUserManageVoList = orderTicketUserList.stream().map(orderTicketUser -> {
                    OrderTicketUserManageVo orderTicketUserManageVo = new OrderTicketUserManageVo();
                    BeanUtils.copyProperties(orderTicketUser, orderTicketUserManageVo);
                    orderTicketUserManageVo.setOrderStatusName(OrderStatus.getMsg(orderTicketUser.getOrderStatus()));
                    return orderTicketUserManageVo;
                }).toList();
                orderManageVo.setOrderTicketUserManageVoList(orderTicketUserManageVoList);
            }
            orderManageVoList.add(orderManageVo);
        }
        BeanUtils.copyProperties(orderProgramPage, orderListManageVoPage);
        orderListManageVoPage.setRecords(orderManageVoList);
        return orderListManageVoPage;
    }
    
    public IPage<DiscardOrderManageVo> discardOrderPage(OrderPageManageDto orderPageManageDto) {
        Long total = redisCache.lenForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, orderPageManageDto.getProgramId()));
        IPage<DiscardOrderManageVo> discardOrderManageVoPage = new Page<>(orderPageManageDto.getPageNumber(), orderPageManageDto.getPageSize(),total);
        long start = (long) (orderPageManageDto.getPageNumber() - 1) * orderPageManageDto.getPageSize();
        long end = start + orderPageManageDto.getPageSize() - 1;
        List<DiscardOrder> discardOrderList = redisCache.rangeForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, orderPageManageDto.getProgramId()),start, end, DiscardOrder.class);
        if (CollectionUtil.isEmpty(discardOrderList)) {
            return discardOrderManageVoPage;
        }
        List<DiscardOrderManageVo> discardOrderManageVoList = new ArrayList<>();
        for (DiscardOrder discardOrder : discardOrderList) {
            DiscardOrderManageVo discardOrderManageVo = new DiscardOrderManageVo();
            BeanUtils.copyProperties(discardOrder.getOrderCreateMq(), discardOrderManageVo);
            discardOrderManageVo.setDiscardOrderReason(discardOrder.getDiscardOrderReason());
            discardOrderManageVo.setDiscardOrderReasonName(DiscardOrderReason.getMsg(discardOrder.getDiscardOrderReason()));
            List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = discardOrder.getOrderCreateMq().getOrderTicketUserCreateDtoList();
            List<DiscardOrderTicketUserManageVo> discardOrderTicketUserManageVoList = new ArrayList<>();
            for (OrderTicketUserCreateDto orderTicketUserCreateDto : orderTicketUserCreateDtoList) {
                DiscardOrderTicketUserManageVo discardOrderTicketUserManageVo = new DiscardOrderTicketUserManageVo();
                BeanUtils.copyProperties(orderTicketUserCreateDto, discardOrderTicketUserManageVo);
                discardOrderTicketUserManageVoList.add(discardOrderTicketUserManageVo);
            }
            discardOrderManageVo.setDiscardOrderTicketUserManageVo(discardOrderTicketUserManageVoList);
            discardOrderManageVoList.add(discardOrderManageVo);
        }
        discardOrderManageVoPage.setRecords(discardOrderManageVoList);
        return discardOrderManageVoPage;
    }
}
