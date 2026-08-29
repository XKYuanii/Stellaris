package com.stellaris.pay.mock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.dto.PayDto;
import com.stellaris.entity.PayBill;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.enums.PayChannel;
import com.stellaris.mapper.PayBillMapper;
import com.stellaris.pay.PayResult;
import com.stellaris.pay.PayStrategyHandler;
import com.stellaris.pay.RefundResult;
import com.stellaris.pay.TradeResult;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/** 不访问第三方平台，但完整参与账单、订单、Intent 和座位状态链路。 */
@RequiredArgsConstructor
public class MockPayStrategyHandler implements PayStrategyHandler {

    private final PayBillMapper payBillMapper;

    @Override
    public PayResult pay(PayDto dto) {
        String outcome = dto.getSimulationOutcome();
        if ("SUCCESS".equalsIgnoreCase(outcome)) {
            return new PayResult(true, "PAID", null, "模拟支付成功");
        }
        if ("FAILURE".equalsIgnoreCase(outcome)) {
            return new PayResult(false, "FAILED", null, "模拟支付失败，订单保持未支付");
        }
        return new PayResult(false, "FAILED", null, "模拟支付必须指定 SUCCESS 或 FAILURE");
    }

    @Override
    public PayResult pay(String outTradeNo, BigDecimal price, String subject, String notifyUrl, String returnUrl) {
        return new PayResult(false, "FAILED", null, "模拟支付缺少受控结果");
    }

    @Override
    public boolean signVerify(Map<String, String> params) {
        return false;
    }

    @Override
    public boolean dataVerify(Map<String, String> params, PayBill payBill) {
        return false;
    }

    @Override
    public TradeResult queryTrade(String outTradeNo) {
        PayBill bill = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                .eq(PayBill::getOutOrderNo, outTradeNo));
        TradeResult result = new TradeResult();
        result.setSuccess(bill != null);
        if (bill != null) {
            result.setOutTradeNo(outTradeNo);
            result.setTotalAmount(bill.getPayAmount());
            result.setPayBillStatus(bill.getPayBillStatus());
        }
        return result;
    }

    @Override
    public RefundResult refund(String outTradeNo, BigDecimal price, String reason, String refundNo) {
        return new RefundResult(true, refundNo, "模拟退款成功");
    }

    @Override
    public String getChannel() {
        return PayChannel.MOCK.getValue();
    }
}
