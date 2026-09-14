package com.stellaris.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.client.PayClient;
import com.stellaris.client.UserClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AccountOrderCountDto;
import com.stellaris.dto.NotifyDto;
import com.stellaris.dto.OrderCancelDto;
import com.stellaris.dto.OrderGetDto;
import com.stellaris.dto.OrderListDto;
import com.stellaris.dto.OrderMaterializationQueryDto;
import com.stellaris.dto.OrderPayCheckDto;
import com.stellaris.dto.OrderPayDto;
import com.stellaris.dto.OrderSimpleListDto;
import com.stellaris.dto.PayDto;
import com.stellaris.dto.RefundDto;
import com.stellaris.dto.TradeCheckDto;
import com.stellaris.dto.UserGetAndTicketUserListDto;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderRequest;
import com.stellaris.entity.OrderStreamFailure;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.entity.OrderTicketUserAggregate;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.BusinessStatus;
import com.stellaris.enums.OrderStatus;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.enums.PayChannel;
import com.stellaris.enums.SellStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderRequestMapper;
import com.stellaris.mapper.OrderStreamFailureMapper;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.mapper.AccountProgramPurchaseMapper;
import com.stellaris.mapper.TradeSeatInventoryMapper;
import com.stellaris.request.CustomizeRequestWrapper;
import com.stellaris.service.properties.OrderProperties;
import com.stellaris.service.reference.ReservationTransitionEventService;
import com.stellaris.service.reference.PaymentReconciliationEventService;
import com.stellaris.util.DateUtils;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.AccountOrderCountVo;
import com.stellaris.vo.NotifyVo;
import com.stellaris.vo.OrderGetVo;
import com.stellaris.vo.OrderListVo;
import com.stellaris.vo.OrderMaterializationVo;
import com.stellaris.vo.OrderPayCheckVo;
import com.stellaris.vo.PayResultVo;
import com.stellaris.vo.OrderTicketInfoVo;
import com.stellaris.vo.TicketUserInfoVo;
import com.stellaris.vo.TicketUserVo;
import com.stellaris.vo.TradeCheckVo;
import com.stellaris.vo.UserAndTicketUserInfoVo;
import com.stellaris.vo.UserGetAndTicketUserListVo;
import com.stellaris.vo.UserInfoVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.stellaris.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 service
 * @author: xz_y
 **/
@Slf4j
@Service
public class OrderService extends ServiceImpl<OrderMapper, Order> {
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;
    
    @Autowired
    private PayClient payClient;
    
    @Autowired
    private UserClient userClient;
    
    @Autowired
    private OrderProperties orderProperties;
    
    @Lazy
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private OrderProgramMapper orderProgramMapper;
    
    @Autowired
    private ReservationTransitionEventService reservationTransitionEventService;

    @Autowired
    private PaymentReconciliationEventService paymentReconciliationEventService;

    @Autowired
    private OrderRequestMapper orderRequestMapper;

    @Autowired
    private OrderStreamFailureMapper orderStreamFailureMapper;

    @Autowired
    private TradeSeatInventoryMapper tradeSeatInventoryMapper;

    @Autowired
    private AccountProgramPurchaseMapper accountProgramPurchaseMapper;

    @Autowired
    private OrderAccessService orderAccessService;

    public PayResultVo pay(OrderPayDto orderPayDto, Long currentUserId) {
        Long orderNumber = orderPayDto.getOrderNumber();
        Order order = orderAccessService.requireOwnedOrder(orderNumber, currentUserId);
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            throw new StellarisFrameException(BaseCode.ORDER_CANCEL);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())
                && Objects.equals(orderPayDto.getChannel(), PayChannel.MOCK.getValue())) {
            return new PayResultVo(PayChannel.MOCK.getValue(), "PAID", null, "订单已支付");
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) {
            throw new StellarisFrameException(BaseCode.ORDER_PAY);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            throw new StellarisFrameException(BaseCode.ORDER_REFUND);
        }
        if (orderPayDto.getPrice().compareTo(order.getOrderPrice()) != 0) {
            throw new StellarisFrameException(BaseCode.PAY_PRICE_NOT_EQUAL_ORDER_PRICE);
        }
        PayDto payDto = getPayDto(orderPayDto, orderNumber);
        // Persist reconciliation evidence before the remote payment call. A process crash after
        // the channel accepts payment can then be recovered without a browser poll or callback.
        paymentReconciliationEventService.track(orderNumber);
        ApiResponse<PayResultVo> payResponse = payClient.commonPay(payDto);
        if (!Objects.equals(payResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new StellarisFrameException(payResponse);
        }
        PayResultVo result = Optional.ofNullable(payResponse.getData())
                .orElseThrow(() -> new StellarisFrameException(BaseCode.RPC_RESULT_DATA_EMPTY));
        if (Objects.equals(result.getState(), "PAID")) {
            try {
                orderService.updateOrderRelatedData(orderNumber, OrderStatus.PAY);
            } catch (RuntimeException ex) {
                log.error("渠道已确认支付，订单状态等待 pay/check 收敛 orderNumber:{}", orderNumber, ex);
                result.setState("PENDING");
                result.setMessage("支付已确认，订单状态同步中");
            }
        }
        return result;
    }
    
    private PayDto getPayDto(OrderPayDto orderPayDto, Long orderNumber) {
        PayDto payDto = new PayDto();
        payDto.setOrderNumber(String.valueOf(orderNumber));
        payDto.setPayBillType(orderPayDto.getPayBillType());
        payDto.setSubject(orderPayDto.getSubject());
        payDto.setChannel(orderPayDto.getChannel());
        payDto.setSimulationOutcome(orderPayDto.getSimulationOutcome());
        payDto.setPlatform(orderPayDto.getPlatform());
        payDto.setPrice(orderPayDto.getPrice());
        payDto.setNotifyUrl(orderProperties.getOrderPayNotifyUrl());
        payDto.setReturnUrl(orderProperties.getOrderPayReturnUrl());
        return payDto;
    }
    
    /** 支付后订单检查；交易库状态 CAS 吸收重复通知和支付/取消竞争。 */
    public OrderPayCheckVo payCheck(OrderPayCheckDto orderPayCheckDto, Long currentUserId){
        OrderPayCheckVo orderPayCheckVo = new OrderPayCheckVo();
        String payChannel = Optional.ofNullable(PayChannel.getRc(orderPayCheckDto.getPayChannelType()))
                .map(PayChannel::getValue)
                .orElseThrow(() -> new StellarisFrameException(BaseCode.PAY_CHANNEL_NOT_EXIST));
        Order order = orderAccessService.requireOwnedOrder(orderPayCheckDto.getOrderNumber(), currentUserId);
        BeanUtil.copyProperties(order,orderPayCheckVo);
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            RefundDto refundDto = new RefundDto();
            refundDto.setOrderNumber(String.valueOf(order.getOrderNumber()));
            refundDto.setAmount(order.getOrderPrice());
            refundDto.setChannel(payChannel);
            refundDto.setReason("延迟订单关闭");
            ApiResponse<String> response = payClient.refund(refundDto);
            if (response.getCode().equals(BaseCode.SUCCESS.getCode())) {
                Order updateOrder = new Order();
                updateOrder.setEditTime(DateUtils.now());
                updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
                int updated = orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                        .eq(Order::getOrderNumber, order.getOrderNumber())
                        .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode()));
                if (updated == 1) {
                    orderPayCheckVo.setOrderStatus(OrderStatus.REFUND.getCode());
                    orderPayCheckVo.setCancelOrderTime(DateUtils.now());
                }
            }else {
                log.error("pay服务退款失败 dto : {} response : {}",JSON.toJSONString(refundDto),JSON.toJSONString(response));
            }
            return orderPayCheckVo;
        }
        
        TradeCheckDto tradeCheckDto = new TradeCheckDto();
        tradeCheckDto.setOutTradeNo(String.valueOf(orderPayCheckDto.getOrderNumber()));
        tradeCheckDto.setChannel(payChannel);
        ApiResponse<TradeCheckVo> tradeCheckVoApiResponse = payClient.tradeCheck(tradeCheckDto);
        if (!Objects.equals(tradeCheckVoApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new StellarisFrameException(tradeCheckVoApiResponse);
        }
        TradeCheckVo tradeCheckVo = Optional.ofNullable(tradeCheckVoApiResponse.getData())
                .orElseThrow(() -> new StellarisFrameException(BaseCode.PAY_BILL_NOT_EXIST));
        if (tradeCheckVo.isSuccess()) {
            Integer payBillStatus = tradeCheckVo.getPayBillStatus();
            Integer orderStatus = order.getOrderStatus();
            if (!Objects.equals(orderStatus, payBillStatus)) {
                orderPayCheckVo.setOrderStatus(payBillStatus);
                try {
                    if (Objects.equals(payBillStatus, PayBillStatus.PAY.getCode())) {
                        orderPayCheckVo.setPayOrderTime(DateUtils.now());
                        orderService.updateOrderRelatedData(order.getOrderNumber(),OrderStatus.PAY);
                    }else if (Objects.equals(payBillStatus, PayBillStatus.CANCEL.getCode())) {
                        orderPayCheckVo.setCancelOrderTime(DateUtils.now());
                        orderService.updateOrderRelatedData(order.getOrderNumber(),OrderStatus.CANCEL);
                    }
                }catch (Exception e) {
                    log.warn("updateOrderRelatedData warn message",e);
                }
            }
        }else {
            throw new StellarisFrameException(BaseCode.PAY_TRADE_CHECK_ERROR);
        }
        return orderPayCheckVo;
    }
    
    
    public String alipayNotify(HttpServletRequest request){
        
        Map<String, String> params = new HashMap<>(256);
        if (request instanceof final CustomizeRequestWrapper customizeRequestWrapper) {
            String requestBody = customizeRequestWrapper.getRequestBody();
            params = StringUtil.convertQueryStringToMap(requestBody);
        }
        log.info("收到支付宝回调通知 params : {}",JSON.toJSONString(params));
        String outTradeNo = params.get("out_trade_no");
        if (StringUtil.isEmpty(outTradeNo)) {
            return "failure";
        }
        
        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderNumber, Long.parseLong(outTradeNo)));
            if (Objects.isNull(order)) {
                throw new StellarisFrameException(BaseCode.ORDER_NOT_EXIST);
            }
            if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
                RefundDto refundDto = new RefundDto();
                refundDto.setOrderNumber(outTradeNo);
                refundDto.setAmount(order.getOrderPrice());
                refundDto.setChannel("alipay");
                refundDto.setReason("延迟订单关闭");
                ApiResponse<String> response = payClient.refund(refundDto);
                if (response.getCode().equals(BaseCode.SUCCESS.getCode())) {
                    Order updateOrder = new Order();
                    updateOrder.setEditTime(DateUtils.now());
                    updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
                    orderMapper.update(updateOrder,Wrappers.lambdaUpdate(Order.class).eq(Order::getOrderNumber, outTradeNo));
                }else {
                    log.error("pay服务退款失败 dto : {} response : {}",JSON.toJSONString(refundDto),JSON.toJSONString(response));
                }
                return ALIPAY_NOTIFY_SUCCESS_RESULT;
            }
          
            
            NotifyDto notifyDto = new NotifyDto();
            notifyDto.setChannel(PayChannel.ALIPAY.getValue());
            notifyDto.setParams(params);
            ApiResponse<NotifyVo> notifyResponse = payClient.notify(notifyDto);
            if (!Objects.equals(notifyResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                throw new StellarisFrameException(notifyResponse);
            }
            if (ALIPAY_NOTIFY_SUCCESS_RESULT.equals(notifyResponse.getData().getPayResult())) {
                try {
                    orderService.updateOrderRelatedData(Long.parseLong(notifyResponse.getData().getOutTradeNo())
                            ,OrderStatus.PAY);
                }catch (Exception e) {
                    log.warn("updateOrderRelatedData warn message",e);
                }
            }
        return notifyResponse.getData().getPayResult();
    }
    
    /**
     * 更新订单和购票人订单状态以及操作缓存数据
     * */
    @Transactional(rollbackFor = Exception.class)
    public void updateOrderRelatedData(Long orderNumber,OrderStatus orderStatus){
        //如果不是取消或者支付操作，则直接抛出异常提示
        if (!(Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode()) ||
                Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()))) {
            throw new StellarisFrameException(  BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT);
        }
        //查询订单
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber);
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        if (Objects.isNull(order)) {
            throw new StellarisFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        // 同目标重复通知是幂等成功；相反目标必须拒绝，不能让支付和超时取消相互覆盖。
        if (Objects.equals(order.getOrderStatus(), orderStatus.getCode())) {
            return;
        }
        if (!Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            throw orderStatusConflict(order);
        }
        //查询该订单下的购票人订单列表
        LambdaQueryWrapper<OrderTicketUser> orderTicketUserLambdaQueryWrapper =
                Wrappers.lambdaQuery(OrderTicketUser.class).eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(orderTicketUserLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderTicketUserList)) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_ORDER_NOT_EXIST);
        }
        //将订单更新为取消或者支付状态
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setOrderStatus(orderStatus.getCode());
        //将购票人订单更新为取消或者支付状态
        OrderTicketUser updateOrderTicketUser = new OrderTicketUser();
        updateOrderTicketUser.setOrderStatus(orderStatus.getCode());

        //支付状态的操作
        if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())) {
            updateOrder.setPayOrderTime(DateUtils.now());
            updateOrderTicketUser.setPayOrderTime(DateUtils.now());
        } else if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
            //取消状态的操作
            updateOrder.setCancelOrderTime(DateUtils.now());
            updateOrderTicketUser.setCancelOrderTime(DateUtils.now());
        }
        //更新订单
        LambdaUpdateWrapper<Order> orderLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(Order.class)
                        .eq(Order::getOrderNumber, order.getOrderNumber())
                        .eq(Order::getOrderStatus, OrderStatus.NO_PAY.getCode());
        int updateOrderResult = orderMapper.update(updateOrder,orderLambdaUpdateWrapper);
        if (updateOrderResult != 1) {
            Order latest = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderNumber));
            if (latest != null && Objects.equals(latest.getOrderStatus(), orderStatus.getCode())) {
                return;
            }
            throw orderStatusConflict(latest);
        }
        //更新购票人订单
        LambdaUpdateWrapper<OrderTicketUser> orderTicketUserLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(OrderTicketUser.class)
                        .eq(OrderTicketUser::getOrderNumber, order.getOrderNumber())
                        .eq(OrderTicketUser::getOrderStatus, OrderStatus.NO_PAY.getCode());
        int updateTicketUserOrderResult =
                orderTicketUserMapper.update(updateOrderTicketUser,orderTicketUserLambdaUpdateWrapper);
        if (updateTicketUserOrderResult != orderTicketUserList.size()) {
            throw new StellarisFrameException(BaseCode.ORDER_CANAL_ERROR);
        }
        int targetSeatStatus = Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())
                ? SellStatus.SOLD.getCode() : SellStatus.NO_SOLD.getCode();
        int transitionedSeats = tradeSeatInventoryMapper.transitionOrderSeats(order.getProgramId(),
                order.getOrderNumber(), order.getIntentId(), SellStatus.LOCK.getCode(), targetSeatStatus);
        if (transitionedSeats != orderTicketUserList.size()) {
            throw new IllegalStateException("seat inventory transition count mismatch order=" + orderNumber);
        }
        // 取消时在同一个本地事务返还权威限购额度。
        if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
            int released = accountProgramPurchaseMapper.decrement(order.getProgramId(), order.getUserId(),
                    updateTicketUserOrderResult);
            if (released != 1) {
                throw new IllegalStateException("account purchase counter release failed order=" + orderNumber);
            }
        }
        Long programId = order.getProgramId();
        //将购票人订单集合转换成map结构，key：票档id value：购票人订单
        Map<Long, List<OrderTicketUser>> orderTicketUserSeatList = 
                orderTicketUserList.stream().collect(Collectors.groupingBy(OrderTicketUser::getTicketCategoryId));
        Map<Long,List<Long>> seatMap = new HashMap<>(orderTicketUserSeatList.size());
        //根据orderTicketUserSeatList得到seatMap
        //seatMap结构 key：票档id  value：座位id集合
        orderTicketUserSeatList.forEach((k,v) -> {
            seatMap.put(k,v.stream().map(OrderTicketUser::getSeatId).collect(Collectors.toList()));
        });
        // 权威事务提交后只同步 Redis；失败由事件表重试。
        enqueueReservationTransition(programId, seatMap, orderStatus, order.getUserId(),
                order.getIntentId(), order.getOrderNumber());
    }

    private StellarisFrameException orderStatusConflict(Order order) {
        if (order == null) {
            return new StellarisFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            return new StellarisFrameException(BaseCode.ORDER_CANCEL);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) {
            return new StellarisFrameException(BaseCode.ORDER_PAY);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            return new StellarisFrameException(BaseCode.ORDER_REFUND);
        }
        return new StellarisFrameException(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT);
    }
    
    public void checkOrderStatus(Order order){
        if (Objects.isNull(order)) {
            throw new StellarisFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            throw new StellarisFrameException(BaseCode.ORDER_CANCEL);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) {
            throw new StellarisFrameException(BaseCode.ORDER_PAY);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            throw new StellarisFrameException(BaseCode.ORDER_REFUND);
        }
    }
    
    private void enqueueReservationTransition(Long programId, Map<Long,List<Long>> seatMap,
                                              OrderStatus orderStatus, Long userId,
                                              String intentId, Long orderNumber) {
        int targetSellStatus = Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())
                ? SellStatus.SOLD.getCode() : SellStatus.NO_SOLD.getCode();
        reservationTransitionEventService.enqueue(orderNumber, userId, programId, intentId, targetSellStatus);
    }
    
    public List<OrderListVo> selectList(OrderListDto orderListDto, Long currentUserId) {
        orderListDto.setUserId(currentUserId);
        List<OrderListVo> orderListVos = new ArrayList<>();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper = 
                Wrappers.lambdaQuery(Order.class)
                        .eq(Order::getUserId, currentUserId)
                        .orderByDesc(Order::getCreateOrderTime);
        List<Order> orderList = orderMapper.selectList(orderLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderList)) {
            return orderListVos;
        }
        orderListVos = BeanUtil.copyToList(orderList, OrderListVo.class);
        List<OrderTicketUserAggregate> orderTicketUserAggregateList = 
                orderTicketUserMapper.selectOrderTicketUserAggregate(orderList.stream().map(Order::getOrderNumber).
                        collect(Collectors.toList()));
        Map<Long, Integer> orderTicketUserAggregateMap = orderTicketUserAggregateList.stream()
                .collect(Collectors.toMap(OrderTicketUserAggregate::getOrderNumber, 
                        OrderTicketUserAggregate::getOrderTicketUserCount, (v1, v2) -> v2));
        for (OrderListVo orderListVo : orderListVos) {
            orderListVo.setTicketCount(orderTicketUserAggregateMap.get(orderListVo.getOrderNumber()));
        }
        return orderListVos;
    }
    
    public OrderGetVo get(OrderGetDto orderGetDto, Long currentUserId) {
        Order order = orderAccessService.requireOwnedOrder(orderGetDto.getOrderNumber(), currentUserId);
        LambdaQueryWrapper<OrderTicketUser> orderTicketUserLambdaQueryWrapper = 
                Wrappers.lambdaQuery(OrderTicketUser.class).eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(orderTicketUserLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderTicketUserList)) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_ORDER_NOT_EXIST);   
        }
        
        OrderGetVo orderGetVo = new OrderGetVo();
        BeanUtil.copyProperties(order,orderGetVo);
        
        List<OrderTicketInfoVo> orderTicketInfoVoList = new ArrayList<>();
        Map<BigDecimal, List<OrderTicketUser>> orderTicketUserMap = 
                orderTicketUserList.stream().collect(Collectors.groupingBy(OrderTicketUser::getOrderPrice));
        orderTicketUserMap.forEach((k,v) -> {
            OrderTicketInfoVo orderTicketInfoVo = new OrderTicketInfoVo();
            String seatInfo = "暂无座位信息";
            if (order.getProgramPermitChooseSeat().equals(BusinessStatus.YES.getCode())) {
                seatInfo = v.stream().map(OrderTicketUser::getSeatInfo).collect(Collectors.joining(","));
            }
            orderTicketInfoVo.setSeatInfo(seatInfo);
            orderTicketInfoVo.setPrice(v.get(0).getOrderPrice());
            orderTicketInfoVo.setQuantity(v.size());
            orderTicketInfoVo.setRelPrice(v.stream().map(OrderTicketUser::getOrderPrice)
                    .reduce(BigDecimal.ZERO,BigDecimal::add));
            orderTicketInfoVoList.add(orderTicketInfoVo);
        });
        
        orderGetVo.setOrderTicketInfoVoList(orderTicketInfoVoList);
        
        UserGetAndTicketUserListDto userGetAndTicketUserListDto = new UserGetAndTicketUserListDto();
        userGetAndTicketUserListDto.setUserId(order.getUserId());
        ApiResponse<UserGetAndTicketUserListVo> userGetAndTicketUserApiResponse = 
                userClient.getUserAndTicketUserList(userGetAndTicketUserListDto);
        
        if (!Objects.equals(userGetAndTicketUserApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new StellarisFrameException(userGetAndTicketUserApiResponse);
            
        }
        UserGetAndTicketUserListVo userAndTicketUserListVo =
                Optional.ofNullable(userGetAndTicketUserApiResponse.getData())
                        .orElseThrow(() -> new StellarisFrameException(BaseCode.RPC_RESULT_DATA_EMPTY));
        if (Objects.isNull(userAndTicketUserListVo.getUserVo())) {
            throw new StellarisFrameException(BaseCode.USER_EMPTY);
        }
        if (CollectionUtil.isEmpty(userAndTicketUserListVo.getTicketUserVoList())) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_EMPTY);
        }
        List<TicketUserVo> filterTicketUserVoList = new ArrayList<>();
        Map<Long, TicketUserVo> ticketUserVoMap = userAndTicketUserListVo.getTicketUserVoList()
                .stream().collect(Collectors.toMap(TicketUserVo::getId, ticketUserVo -> ticketUserVo, (v1, v2) -> v2));
        for (OrderTicketUser orderTicketUser : orderTicketUserList) {
            filterTicketUserVoList.add(ticketUserVoMap.get(orderTicketUser.getTicketUserId()));
        }
        UserInfoVo userInfoVo = new UserInfoVo();
        BeanUtil.copyProperties(userAndTicketUserListVo.getUserVo(),userInfoVo);
        UserAndTicketUserInfoVo userAndTicketUserInfoVo = new UserAndTicketUserInfoVo();
        userAndTicketUserInfoVo.setUserInfoVo(userInfoVo);
        userAndTicketUserInfoVo.setTicketUserInfoVoList(BeanUtil.copyToList(filterTicketUserVoList, TicketUserInfoVo.class));
        orderGetVo.setUserAndTicketUserInfoVo(userAndTicketUserInfoVo);
        
        return orderGetVo;
    }
    
    public AccountOrderCountVo accountOrderCount(AccountOrderCountDto accountOrderCountDto) {
        AccountOrderCountVo accountOrderCountVo = new AccountOrderCountVo();
        Integer count = accountProgramPurchaseMapper.currentCount(accountOrderCountDto.getProgramId(),
                accountOrderCountDto.getUserId());
        accountOrderCountVo.setCount(count == null ? 0 : count);
        return accountOrderCountVo;
    }
    
    
    public OrderMaterializationVo materialization(OrderMaterializationQueryDto dto, Long currentUserId) {
        OrderRequest request = orderRequestMapper.selectOne(Wrappers.lambdaQuery(OrderRequest.class)
                .eq(OrderRequest::getOrderNumber, dto.getOrderNumber())
                .eq(OrderRequest::getUserId, currentUserId));
        OrderMaterializationVo result = new OrderMaterializationVo();
        result.setOrderNumber(dto.getOrderNumber());
        if (request != null) {
            result.setStatus(request.getResultStatus());
            result.setRejectCode(request.getRejectCode());
            return result;
        }
        OrderStreamFailure failure = orderStreamFailureMapper.selectOne(
                Wrappers.lambdaQuery(OrderStreamFailure.class)
                        .eq(OrderStreamFailure::getOrderNumber, dto.getOrderNumber())
                        .eq(OrderStreamFailure::getUserId, currentUserId)
                        .in(OrderStreamFailure::getRecordStatus, "RECORDED", "MANUAL_REQUIRED")
                        .orderByDesc(OrderStreamFailure::getCreateTime)
                        .last("LIMIT 1"));
        result.setStatus(failure == null ? "PROCESSING" : "REJECTED");
        result.setRejectCode(failure == null ? null : "ORDER_EVENT_AUDITED");
        return result;
    }
    
    @Transactional(rollbackFor = Exception.class)
    public boolean initiateCancel(OrderCancelDto orderCancelDto, Long currentUserId){
        Order order = orderAccessService.requireOwnedOrder(orderCancelDto.getOrderNumber(), currentUserId);
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            return true;
        }
        if (!Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            throw new StellarisFrameException(BaseCode.CAN_NOT_CANCEL);
        }
        updateOrderRelatedData(orderCancelDto.getOrderNumber(), OrderStatus.CANCEL);
        return true;
    }
    
    
    public void delOrderAndOrderTicketUser(){
        orderMapper.relDelOrder();
        orderTicketUserMapper.relDelOrderTicketUser();
        orderProgramMapper.relDelOrderProgram();
    }
    
    public List<OrderListVo> simpleList(OrderSimpleListDto orderSimpleListDto) {
        if (Objects.isNull(orderSimpleListDto.getOrderNumber()) && Objects.isNull(orderSimpleListDto.getUserId())) {
            throw new StellarisFrameException(BaseCode.USER_ID_AND_ORDER_NUMBER_NOT_EXIST);
        }
        List<OrderListVo> orderListVos = new ArrayList<>();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class)
                        .eq(Objects.nonNull(orderSimpleListDto.getOrderNumber()),Order::getOrderNumber, orderSimpleListDto.getOrderNumber())
                        .eq(Objects.nonNull(orderSimpleListDto.getUserId()),Order::getUserId, orderSimpleListDto.getUserId())
                        .orderByDesc(Order::getCreateOrderTime);
        List<Order> orderList = orderMapper.selectList(orderLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderList)) {
            return orderListVos;
        }
        return BeanUtil.copyToList(orderList, OrderListVo.class);
    }
}
