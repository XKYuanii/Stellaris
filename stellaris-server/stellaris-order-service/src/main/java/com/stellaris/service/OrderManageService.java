package com.stellaris.service;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.domain.DiscardOrder;
import com.stellaris.dto.OrderPageManageDto;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderProgram;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.enums.DiscardOrderReason;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.page.PageUtil;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.vo.DiscardOrderManageVo;
import com.stellaris.vo.DiscardOrderTicketUserManageVo;
import com.stellaris.vo.OrderManageVo;
import com.stellaris.vo.OrderTicketUserManageVo;
import groovy.util.logging.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    private RedisCache redisCache;
    
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
