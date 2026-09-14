package com.stellaris.service.reference;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.client.PayClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.dto.RefundDto;
import com.stellaris.entity.Order;
import com.stellaris.entity.PaymentReconciliationEvent;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.OrderStatus;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.service.OrderService;
import com.stellaris.util.DateUtils;
import com.stellaris.vo.ReferencePayStateVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Converges payment bills and local orders without requiring a browser poll or channel callback. */
@Slf4j
@Component
public class PaymentReconciliationTask {
    private final PaymentReconciliationEventService eventService;
    private final PayClient payClient;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    @Value("${payment-reconciliation.remote-batch-size:20}")
    private int remoteBatchSize;

    public PaymentReconciliationTask(PaymentReconciliationEventService eventService, PayClient payClient,
                                     OrderMapper orderMapper, OrderService orderService) {
        this.eventService = eventService;
        this.payClient = payClient;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${payment-reconciliation.fixed-delay-ms:10000}")
    public void reconcile() {
        List<PaymentReconciliationEvent> events = eventService.claimDue();
        if (events.isEmpty()) return;
        int chunkSize = Math.max(1, Math.min(remoteBatchSize, 500));
        for (int from = 0; from < events.size(); from += chunkSize) {
            reconcileBatch(events.subList(from, Math.min(from + chunkSize, events.size())));
        }
    }

    private void reconcileBatch(List<PaymentReconciliationEvent> events) {
        try {
            ReferencePayStateQueryDto query = new ReferencePayStateQueryDto();
            query.setOutOrderNos(events.stream().map(event -> String.valueOf(event.getOrderNumber())).toList());
            ApiResponse<List<ReferencePayStateVo>> response = payClient.referenceStateBatch(query);
            if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())
                    || response.getData() == null) {
                throw new IllegalStateException(response == null ? "null payment reconciliation response"
                        : response.getMessage());
            }
            Map<String, ReferencePayStateVo> states = response.getData().stream()
                    .collect(Collectors.toMap(ReferencePayStateVo::getOutOrderNo, Function.identity(), (a, b) -> b));
            for (PaymentReconciliationEvent event : events) {
                try {
                    ReferencePayStateVo state = states.get(String.valueOf(event.getOrderNumber()));
                    if (state == null) {
                        reconcileMissingBill(event.getOrderNumber());
                    } else {
                        reconcileOne(event.getOrderNumber(), state);
                    }
                    eventService.succeeded(event);
                } catch (PaymentReconciliationPendingException pending) {
                    eventService.waiting(event, pending.getMessage());
                } catch (PaymentReconciliationConflictException conflict) {
                    eventService.dead(event, conflict);
                    log.error("支付对账发现确定性冲突，停止自动重试 orderNumber:{} code:{}",
                            event.getOrderNumber(), conflict.getCode(), conflict);
                } catch (RuntimeException failure) {
                    eventService.failed(event, failure);
                    log.warn("支付对账未收敛 orderNumber:{}", event.getOrderNumber(), failure);
                }
            }
        } catch (RuntimeException batchFailure) {
            events.forEach(event -> eventService.failed(event, batchFailure));
            log.warn("支付事实批量查询失败，事件保留等待重试", batchFailure);
        }
    }

    void reconcileOne(long orderNumber, ReferencePayStateVo payState) {
        Order order = currentOrder(orderNumber);
        Integer billStatus = payState.getPayBillStatus();
        if (billStatus == null) throw conflict("MISSING_BILL_STATUS", "payment bill status is missing");
        if ((Objects.equals(billStatus, PayBillStatus.PAY.getCode())
                || Objects.equals(billStatus, PayBillStatus.REFUND.getCode()))
                && (payState.getPayAmount() == null
                || payState.getPayAmount().compareTo(order.getOrderPrice()) != 0)) {
            throw conflict("AMOUNT_MISMATCH", "payment amount does not match order amount");
        }
        if (Objects.equals(billStatus, PayBillStatus.NO_PAY.getCode())) {
            if (!Boolean.TRUE.equals(payState.getChannelVerified())) {
                throw new IllegalStateException("local unpaid bill has not been verified with payment channel");
            }
            if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())
                    || Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) return;
            throw new PaymentReconciliationPendingException("payment is still pending");
        }
        if (Objects.equals(billStatus, PayBillStatus.CANCEL.getCode())) {
            if (Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
                orderService.updateOrderRelatedData(orderNumber, OrderStatus.CANCEL);
                return;
            }
            if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())
                    || Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) return;
            throw conflict("CANCELLED_BILL_ORDER_CONFLICT",
                    "paid order conflicts with a cancelled payment bill");
        }
        if (Objects.equals(billStatus, PayBillStatus.PAY.getCode())) {
            convergePaid(order, payState);
            return;
        }
        if (Objects.equals(billStatus, PayBillStatus.REFUND.getCode())) {
            convergeRefunded(order);
            return;
        }
        throw conflict("UNSUPPORTED_BILL_STATUS", "unsupported payment bill status: " + billStatus);
    }

    /**
     * A successful authoritative batch query with no bill means createBill never committed. Once the
     * order is terminal there is no remote fact left to converge, so retaining the event would only
     * create an endless retry. An open order keeps retrying because its create request may still be in flight.
     */
    void reconcileMissingBill(long orderNumber) {
        Order order = currentOrder(orderNumber);
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())
                || Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) return;
        if (!Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            throw conflict("MISSING_BILL_ORDER_CONFLICT",
                    "payment bill is missing for order status " + order.getOrderStatus());
        }
        throw new PaymentReconciliationPendingException("payment bill has not materialized yet");
    }

    private void convergePaid(Order order, ReferencePayStateVo state) {
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())
                || Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) return;
        if (Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            try {
                orderService.updateOrderRelatedData(order.getOrderNumber(), OrderStatus.PAY);
                return;
            } catch (RuntimeException race) {
                order = currentOrder(order.getOrderNumber());
                if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) return;
                if (!Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) throw race;
            }
        }
        if (!Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            throw conflict("PAID_BILL_ORDER_CONFLICT",
                    "cannot reconcile paid bill with order status " + order.getOrderStatus());
        }
        RefundDto refund = new RefundDto();
        refund.setOrderNumber(String.valueOf(order.getOrderNumber()));
        refund.setAmount(order.getOrderPrice());
        refund.setChannel(state.getPayChannel());
        refund.setReason("支付成功时订单已关闭，服务端自动对账退款");
        ApiResponse<String> response = payClient.refund(refund);
        if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new IllegalStateException(response == null ? "null refund response" : response.getMessage());
        }
        markRefunded(order.getOrderNumber());
    }

    private void convergeRefunded(Order order) {
        if (Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) return;
        if (Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            orderService.updateOrderRelatedData(order.getOrderNumber(), OrderStatus.CANCEL);
            order = currentOrder(order.getOrderNumber());
        }
        if (!Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            throw conflict("REFUNDED_BILL_ORDER_CONFLICT",
                    "refunded bill conflicts with order status " + order.getOrderStatus());
        }
        markRefunded(order.getOrderNumber());
    }

    private void markRefunded(long orderNumber) {
        Order update = new Order();
        update.setOrderStatus(OrderStatus.REFUND.getCode());
        update.setEditTime(DateUtils.now());
        int changed = orderMapper.update(update, Wrappers.lambdaUpdate(Order.class)
                .eq(Order::getOrderNumber, orderNumber)
                .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode()));
        if (changed != 1 && !Objects.equals(currentOrder(orderNumber).getOrderStatus(), OrderStatus.REFUND.getCode())) {
            throw new IllegalStateException("cannot mark reconciled order as refunded");
        }
    }

    private Order currentOrder(long orderNumber) {
        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderNumber, orderNumber));
        if (order == null) throw conflict("MISSING_ORDER", "order does not exist: " + orderNumber);
        return order;
    }

    private PaymentReconciliationConflictException conflict(String code, String message) {
        return new PaymentReconciliationConflictException(code, message);
    }
}
