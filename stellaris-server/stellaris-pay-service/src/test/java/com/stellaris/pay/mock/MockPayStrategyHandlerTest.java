package com.stellaris.pay.mock;

import com.stellaris.dto.PayDto;
import com.stellaris.entity.PayBill;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.mapper.PayBillMapper;
import com.stellaris.pay.PayResult;
import com.stellaris.pay.RefundResult;
import com.stellaris.pay.TradeResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockPayStrategyHandlerTest {

    @Test
    void explicitlyControlsSuccessAndFailure() {
        MockPayStrategyHandler handler = new MockPayStrategyHandler(mock(PayBillMapper.class));
        PayDto dto = new PayDto();
        dto.setSimulationOutcome("SUCCESS");
        PayResult success = handler.pay(dto);
        dto.setSimulationOutcome("FAILURE");
        PayResult failure = handler.pay(dto);

        assertThat(success.isSuccess()).isTrue();
        assertThat(success.getState()).isEqualTo("PAID");
        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.getState()).isEqualTo("FAILED");
    }

    @Test
    void tradeCheckUsesPersistedBillFact() {
        PayBillMapper mapper = mock(PayBillMapper.class);
        PayBill bill = new PayBill();
        bill.setOutOrderNo("1001");
        bill.setPayAmount(new BigDecimal("88.00"));
        bill.setPayBillStatus(PayBillStatus.PAY.getCode());
        when(mapper.selectOne(any())).thenReturn(bill);

        TradeResult result = new MockPayStrategyHandler(mapper).queryTrade("1001");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getPayBillStatus()).isEqualTo(PayBillStatus.PAY.getCode());
        assertThat(result.getTotalAmount()).isEqualByComparingTo("88.00");
    }

    @Test
    void refundReturnsTheStableRefundNumber() {
        MockPayStrategyHandler handler = new MockPayStrategyHandler(mock(PayBillMapper.class));

        RefundResult result = handler.refund("1001", BigDecimal.TEN, "test", "refund-1001");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getBody()).isEqualTo("refund-1001");
    }
}
