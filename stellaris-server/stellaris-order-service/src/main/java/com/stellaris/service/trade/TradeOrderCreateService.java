package com.stellaris.service.trade;

import cn.hutool.core.bean.BeanUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderProgram;
import com.stellaris.entity.OrderRequest;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.AccountProgramPurchaseMapper;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.mapper.OrderRequestMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.mapper.TradeSeatInventoryMapper;
import com.stellaris.service.reference.ReservationTransitionEventService;
import com.stellaris.util.DateUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stream 消息到单交易库的权威落单事务。 */
@Service
public class TradeOrderCreateService {
    private final OrderRequestMapper requestMapper;
    private final AccountProgramPurchaseMapper purchaseMapper;
    private final TradeSeatInventoryMapper inventoryMapper;
    private final OrderMapper orderMapper;
    private final OrderTicketUserMapper ticketUserMapper;
    private final OrderProgramMapper orderProgramMapper;
    private final ReservationTransitionEventService transitionEventService;
    private final UidGenerator uidGenerator;

    public TradeOrderCreateService(OrderRequestMapper requestMapper,
                                   AccountProgramPurchaseMapper purchaseMapper,
                                   TradeSeatInventoryMapper inventoryMapper,
                                   OrderMapper orderMapper,
                                   OrderTicketUserMapper ticketUserMapper,
                                   OrderProgramMapper orderProgramMapper,
                                   ReservationTransitionEventService transitionEventService,
                                   UidGenerator uidGenerator) {
        this.requestMapper = requestMapper;
        this.purchaseMapper = purchaseMapper;
        this.inventoryMapper = inventoryMapper;
        this.orderMapper = orderMapper;
        this.ticketUserMapper = ticketUserMapper;
        this.orderProgramMapper = orderProgramMapper;
        this.transitionEventService = transitionEventService;
        this.uidGenerator = uidGenerator;
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(OrderCreateEvent message) {
        validate(message);
        int acquired = requestMapper.acquire(message.getIntentId(), message.getProgramId(), message.getUserId(),
                message.getRequestId(), message.getOrderNumber(), message.getRequestFingerprint());
        if (acquired == 0) {
            return existingResult(message);
        }

        List<OrderTicketUserCreateDto> tickets = message.getOrderTicketUserCreateDtoList().stream()
                .sorted(Comparator.comparing(OrderTicketUserCreateDto::getSeatId)).toList();
        BigDecimal detailTotal = tickets.stream().map(OrderTicketUserCreateDto::getOrderPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (message.getOrderPrice().compareTo(detailTotal) != 0) {
            throw new OrderReservationRejectedException("ORDER_PRICE_MISMATCH");
        }
        int amount = tickets.size();
        purchaseMapper.initialize(message.getProgramId(), message.getUserId());
        if (purchaseMapper.incrementWithinLimit(message.getProgramId(), message.getUserId(), amount,
                message.getAccountLimit()) != 1) {
            throw new OrderReservationRejectedException("ACCOUNT_LIMIT_EXCEEDED");
        }

        List<SeatInventorySnapshotDto> seats = new ArrayList<>(tickets.size());
        for (OrderTicketUserCreateDto ticket : tickets) {
            if (!Objects.equals(ticket.getProgramId(), message.getProgramId())
                    || !Objects.equals(ticket.getUserId(), message.getUserId())
                    || !Objects.equals(ticket.getOrderNumber(), message.getOrderNumber())) {
                throw new OrderReservationRejectedException("MESSAGE_IDENTITY_MISMATCH");
            }
            SeatInventorySnapshotDto seat = new SeatInventorySnapshotDto();
            seat.setSeatId(ticket.getSeatId());
            seat.setTicketCategoryId(ticket.getTicketCategoryId());
            seat.setPriceInCents(toCents(ticket.getOrderPrice()));
            seats.add(seat);
        }
        // 整单一次 CAS，替代此前每座一条 UPDATE：六座订单的往返从 6 次降到 1 次，
        // 事务持有行锁的时间随之缩短。tickets 已按 seatId 排序，IN 列表随之有序。
        // 影响行数必须等于座位数——少一行就说明有座位被抢、票档或价格已变、
        // 或销售版本过期，此处不区分是哪一座，整单回滚即可。
        int locked = inventoryMapper.lockSeats(message.getProgramId(), message.getSaleVersion(),
                message.getIntentId(), message.getOrderNumber(), seats);
        if (locked != seats.size()) {
            throw new OrderReservationRejectedException("SEAT_NOT_AVAILABLE");
        }

        persistOrder(message, tickets);
        if (requestMapper.markCreated(message.getIntentId()) != 1) {
            throw new IllegalStateException("cannot commit order request result " + message.getIntentId());
        }
        return String.valueOf(message.getOrderNumber());
    }

    /** 创建事务回滚后，以独立短事务形成稳定拒绝结果和 Redis 释放依据。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public OrderRequest recordRejected(OrderCreateEvent message, String rejectCode) {
        validateIdentity(message);
        int inserted = requestMapper.recordRejected(message.getIntentId(), message.getProgramId(), message.getUserId(),
                message.getRequestId(), message.getOrderNumber(), message.getRequestFingerprint(), rejectCode);
        OrderRequest result = findRequest(message);
        validateExisting(message, result);
        if (inserted == 1 && hasReleasableSeats(message)) {
            transitionEventService.enqueue(message.getOrderNumber(), message.getUserId(),
                    message.getProgramId(), message.getIntentId(), com.stellaris.enums.SellStatus.NO_SOLD.getCode());
        }
        return result;
    }

    private String existingResult(OrderCreateEvent message) {
        OrderRequest existing = findRequest(message);
        validateExisting(message, existing);
        if ("CREATED".equals(existing.getResultStatus())) {
            return String.valueOf(existing.getOrderNumber());
        }
        if ("REJECTED".equals(existing.getResultStatus())) {
            throw new OrderReservationRejectedException(existing.getRejectCode());
        }
        throw new IllegalStateException("request is still processing: " + message.getIntentId());
    }

    private OrderRequest findRequest(OrderCreateEvent message) {
        OrderRequest byReservation = requestMapper.selectOne(Wrappers.lambdaQuery(OrderRequest.class)
                .eq(OrderRequest::getReservationId, message.getIntentId()));
        if (byReservation != null) return byReservation;
        return requestMapper.selectOne(Wrappers.lambdaQuery(OrderRequest.class)
                .eq(OrderRequest::getProgramId, message.getProgramId())
                .eq(OrderRequest::getUserId, message.getUserId())
                .eq(OrderRequest::getRequestId, message.getRequestId()));
    }

    private void validateExisting(OrderCreateEvent message, OrderRequest existing) {
        if (existing == null
                || !Objects.equals(existing.getReservationId(), message.getIntentId())
                || !Objects.equals(existing.getProgramId(), message.getProgramId())
                || !Objects.equals(existing.getUserId(), message.getUserId())
                || !Objects.equals(existing.getRequestId(), message.getRequestId())
                || !Objects.equals(existing.getOrderNumber(), message.getOrderNumber())
                || !Objects.equals(existing.getRequestFingerprint(), message.getRequestFingerprint())) {
            throw new OrderReservationRejectedException("IDEMPOTENCY_CONFLICT");
        }
    }

    private void persistOrder(OrderCreateEvent message, List<OrderTicketUserCreateDto> tickets) {
        Order order = new Order();
        BeanUtil.copyProperties(message, order);
        order.setId(uidGenerator.getUid());
        order.setDistributionMode("电子票");
        order.setTakeTicketMode("请使用购票人身份证直接入场");
        order.setOrderStatus(OrderStatus.NO_PAY.getCode());
        order.setExpireTime(message.getReservationExpireTime());
        orderMapper.insert(order);

        for (OrderTicketUserCreateDto source : tickets) {
            OrderTicketUser ticket = new OrderTicketUser();
            BeanUtil.copyProperties(source, ticket);
            ticket.setId(uidGenerator.getUid());
            ticket.setOrderStatus(OrderStatus.NO_PAY.getCode());
            ticketUserMapper.insert(ticket);
        }

        OrderProgram orderProgram = new OrderProgram();
        orderProgram.setId(uidGenerator.getUid());
        orderProgram.setProgramId(message.getProgramId());
        orderProgram.setOrderNumber(message.getOrderNumber());
        orderProgramMapper.insert(orderProgram);
    }

    private boolean hasReleasableSeats(OrderCreateEvent message) {
        return message.getOrderTicketUserCreateDtoList() != null
                && !message.getOrderTicketUserCreateDtoList().isEmpty()
                && message.getOrderTicketUserCreateDtoList().stream()
                .allMatch(ticket -> ticket != null && ticket.getSeatId() != null
                        && ticket.getTicketCategoryId() != null);
    }

    private void validate(OrderCreateEvent message) {
        validateIdentity(message);
        if (message.getAccountLimit() == null || message.getAccountLimit() < 0
                || message.getSaleVersion() == null || message.getSaleVersion().isBlank()
                || message.getReservationExpireTime() == null
                || !message.getReservationExpireTime().after(DateUtils.now())
                || message.getCreateOrderTime() == null
                || message.getOrderPrice() == null
                || message.getProgramPermitChooseSeat() == null
                || exceeds(message.getProgramItemPicture(), 1024)
                || exceeds(message.getProgramTitle(), 512)
                || exceeds(message.getProgramPlace(), 100)
                || invalidMoney(message.getOrderPrice())
                || message.getOrderTicketUserCreateDtoList() == null
                || message.getOrderTicketUserCreateDtoList().isEmpty()
                || message.getOrderTicketUserCreateDtoList().size() > 6
                || message.getOrderTicketUserCreateDtoList().stream().anyMatch(ticket -> ticket == null
                || ticket.getSeatId() == null || ticket.getTicketCategoryId() == null
                || ticket.getTicketUserId() == null || ticket.getOrderPrice() == null
                || ticket.getCreateOrderTime() == null || ticket.getSeatInfo() == null
                || ticket.getSeatInfo().isBlank() || exceeds(ticket.getSeatInfo(), 100)
                || invalidMoney(ticket.getOrderPrice()))
                || message.getOrderTicketUserCreateDtoList().stream()
                .map(OrderTicketUserCreateDto::getSeatId).distinct().count()
                != message.getOrderTicketUserCreateDtoList().size()) {
            throw new OrderReservationRejectedException("INVALID_RESERVATION");
        }
    }

    private void validateIdentity(OrderCreateEvent message) {
        if (message == null || message.getIntentId() == null || message.getIntentId().isBlank()
                || message.getRequestId() == null || message.getRequestId().isBlank()
                || message.getRequestFingerprint() == null || message.getRequestFingerprint().isBlank()
                || exceeds(message.getIntentId(), 64)
                || exceeds(message.getRequestId(), 128)
                || exceeds(message.getRequestFingerprint(), 64)
                || exceeds(message.getSaleVersion(), 64)
                || message.getOrderNumber() == null || message.getProgramId() == null || message.getUserId() == null) {
            throw new IllegalArgumentException("complete reservation identity is required");
        }
    }

    private boolean exceeds(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private boolean invalidMoney(BigDecimal value) {
        return value != null && (value.scale() > 2 || value.precision() - value.scale() > 10);
    }

    private long toCents(BigDecimal price) {
        if (price == null) throw new OrderReservationRejectedException("PRICE_MISSING");
        try {
            return price.movePointRight(2).longValueExact();
        } catch (ArithmeticException ex) {
            throw new OrderReservationRejectedException("PRICE_INVALID");
        }
    }
}
