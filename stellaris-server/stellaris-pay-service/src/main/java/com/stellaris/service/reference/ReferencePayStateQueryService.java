package com.stellaris.service.reference;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.dto.TradeCheckDto;
import com.stellaris.entity.PayBill;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.mapper.PayBillMapper;
import com.stellaris.service.PayService;
import com.stellaris.vo.ReferencePayStateVo;
import com.stellaris.vo.TradeCheckVo;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 向 v5 对账暴露支付事实，并对未支付账单做有上限的渠道查单以弥补回调丢失。 */
@Service
@Slf4j
public class ReferencePayStateQueryService {
    private static final int MAX_BATCH_SIZE = 500;
    private final PayBillMapper payBillMapper;
    private final PayService payService;
    private final MeterRegistry meterRegistry;
    private final Executor paymentChannelExecutor;

    @Value("${payment-channel-reconciliation.max-queries-per-batch:20}")
    private int maxChannelQueriesPerBatch;

    public ReferencePayStateQueryService(PayBillMapper payBillMapper, PayService payService,
                                         MeterRegistry meterRegistry,
                                         @Qualifier("paymentChannelReconciliationExecutor")
                                         Executor paymentChannelExecutor) {
        this.payBillMapper = payBillMapper;
        this.payService = payService;
        this.meterRegistry = meterRegistry;
        this.paymentChannelExecutor = paymentChannelExecutor;
    }

    public List<ReferencePayStateVo> query(ReferencePayStateQueryDto dto) {
        List<String> orderNumbers = dto == null || dto.getOutOrderNos() == null ? List.of()
                : dto.getOutOrderNos().stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (orderNumbers.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("reference reconciliation batch exceeds " + MAX_BATCH_SIZE);
        }
        if (orderNumbers.isEmpty()) {
            return List.of();
        }
        List<PayBill> bills = payBillMapper.selectList(Wrappers.lambdaQuery(PayBill.class)
                .in(PayBill::getOutOrderNo, orderNumbers));
        List<CompletableFuture<ReferencePayStateVo>> states = new ArrayList<>(bills.size());
        int channelQueries = 0;
        int channelLimit = Math.max(0, Math.min(maxChannelQueriesPerBatch, MAX_BATCH_SIZE));
        for (PayBill bill : bills) {
            PayBill latest = bill;
            boolean channelVerified = !Objects.equals(bill.getPayBillStatus(), PayBillStatus.NO_PAY.getCode());
            if (Objects.equals(bill.getPayBillStatus(), PayBillStatus.NO_PAY.getCode())
                    && channelQueries < channelLimit) {
                channelQueries++;
                states.add(CompletableFuture.supplyAsync(() -> {
                    ChannelRefresh refresh = refreshFromChannel(bill);
                    return toState(refresh.bill(), refresh.verified());
                }, paymentChannelExecutor));
            } else {
                states.add(CompletableFuture.completedFuture(toState(latest, channelVerified)));
            }
        }
        return states.stream().map(CompletableFuture::join).toList();
    }

    private ChannelRefresh refreshFromChannel(PayBill bill) {
        try {
            TradeCheckDto query = new TradeCheckDto();
            query.setOutTradeNo(bill.getOutOrderNo());
            query.setChannel(bill.getPayChannel());
            TradeCheckVo channelResult = payService.tradeCheck(query);
            if (channelResult == null || !channelResult.isSuccess()) {
                meterRegistry.counter("stellaris_payment_channel_reconciliation_total", "result", "failed")
                        .increment();
                return new ChannelRefresh(bill, false);
            }
            PayBill latest = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                    .eq(PayBill::getOutOrderNo, bill.getOutOrderNo()));
            meterRegistry.counter("stellaris_payment_channel_reconciliation_total",
                    "result", latest != null && !Objects.equals(latest.getPayBillStatus(), bill.getPayBillStatus())
                            ? "converged" : "unchanged").increment();
            return new ChannelRefresh(latest == null ? bill : latest, true);
        } catch (RuntimeException failure) {
            meterRegistry.counter("stellaris_payment_channel_reconciliation_total", "result", "failed")
                    .increment();
            log.warn("支付渠道查单失败，保留本地未支付事实等待下轮重试 outOrderNo:{} channel:{}",
                    bill.getOutOrderNo(), bill.getPayChannel(), failure);
            return new ChannelRefresh(bill, false);
        }
    }

    private ReferencePayStateVo toState(PayBill payBill, boolean channelVerified) {
        ReferencePayStateVo state = new ReferencePayStateVo();
        state.setOutOrderNo(payBill.getOutOrderNo());
        state.setPayChannel(payBill.getPayChannel());
        state.setPayBillStatus(payBill.getPayBillStatus());
        state.setPayAmount(payBill.getPayAmount());
        state.setChannelVerified(channelVerified);
        return state;
    }

    private record ChannelRefresh(PayBill bill, boolean verified) {
    }
}
