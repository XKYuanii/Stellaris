package com.stellaris.service.reference;

import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.entity.PayBill;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.mapper.PayBillMapper;
import com.stellaris.service.PayService;
import com.stellaris.vo.ReferencePayStateVo;
import com.stellaris.vo.TradeCheckVo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferencePayStateQueryServiceTest {

    @Test
    void unpaidLocalBillIsRefreshedFromChannelBeforeReturning() {
        PayBillMapper mapper = mock(PayBillMapper.class);
        PayService payService = mock(PayService.class);
        ReferencePayStateQueryService service = service(mapper, payService);
        PayBill unpaid = bill(PayBillStatus.NO_PAY);
        PayBill paid = bill(PayBillStatus.PAY);
        when(mapper.selectList(any())).thenReturn(List.of(unpaid));
        when(mapper.selectOne(any())).thenReturn(paid);
        TradeCheckVo channel = new TradeCheckVo();
        channel.setSuccess(true);
        when(payService.tradeCheck(any())).thenReturn(channel);

        List<ReferencePayStateVo> result = service.query(query("1001"));

        verify(payService).tradeCheck(any());
        assertThat(result).singleElement().satisfies(state -> {
            assertThat(state.getPayBillStatus()).isEqualTo(PayBillStatus.PAY.getCode());
            assertThat(state.getPayAmount()).isEqualByComparingTo("88.00");
            assertThat(state.getChannelVerified()).isTrue();
        });
    }

    @Test
    void channelFailureKeepsLocalFactSoDurableOrderEventCanRetry() {
        PayBillMapper mapper = mock(PayBillMapper.class);
        PayService payService = mock(PayService.class);
        ReferencePayStateQueryService service = service(mapper, payService);
        when(mapper.selectList(any())).thenReturn(List.of(bill(PayBillStatus.NO_PAY)));
        doThrow(new IllegalStateException("channel unavailable")).when(payService).tradeCheck(any());

        List<ReferencePayStateVo> result = service.query(query("1001"));

        assertThat(result).singleElement().satisfies(state -> {
            assertThat(state.getPayBillStatus()).isEqualTo(PayBillStatus.NO_PAY.getCode());
            assertThat(state.getChannelVerified()).isFalse();
        });
    }

    @Test
    void unpaidChannelQueriesRunConcurrentlyWithinTheBoundedExecutor() throws Exception {
        PayBillMapper mapper = mock(PayBillMapper.class);
        PayService payService = mock(PayService.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ReferencePayStateQueryService service = service(mapper, payService, executor);
            PayBill first = bill(PayBillStatus.NO_PAY);
            PayBill second = bill(PayBillStatus.NO_PAY);
            second.setOutOrderNo("1002");
            when(mapper.selectList(any())).thenReturn(List.of(first, second));
            CountDownLatch bothStarted = new CountDownLatch(2);
            TradeCheckVo unavailable = new TradeCheckVo();
            unavailable.setSuccess(false);
            when(payService.tradeCheck(any())).thenAnswer(invocation -> {
                bothStarted.countDown();
                if (!bothStarted.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("channel checks ran serially");
                }
                return unavailable;
            });

            assertThat(service.query(query("1001", "1002"))).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private ReferencePayStateQueryService service(PayBillMapper mapper, PayService payService) {
        return service(mapper, payService, Runnable::run);
    }

    private ReferencePayStateQueryService service(PayBillMapper mapper, PayService payService,
                                                   Executor executor) {
        ReferencePayStateQueryService service = new ReferencePayStateQueryService(
                mapper, payService, new SimpleMeterRegistry(), executor);
        ReflectionTestUtils.setField(service, "maxChannelQueriesPerBatch", 20);
        return service;
    }

    private ReferencePayStateQueryDto query(String orderNumber) {
        return query(new String[]{orderNumber});
    }

    private ReferencePayStateQueryDto query(String... orderNumbers) {
        ReferencePayStateQueryDto query = new ReferencePayStateQueryDto();
        query.setOutOrderNos(List.of(orderNumbers));
        return query;
    }

    private PayBill bill(PayBillStatus status) {
        PayBill bill = new PayBill();
        bill.setOutOrderNo("1001");
        bill.setPayChannel("mock");
        bill.setPayBillStatus(status.getCode());
        bill.setPayAmount(new BigDecimal("88.00"));
        return bill;
    }
}
