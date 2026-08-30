package com.stellaris.pay;

import com.stellaris.entity.PayBill;
import com.stellaris.dto.PayDto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付方式 策略抽象
 * @author: xz_y
 **/
public interface PayStrategyHandler {
    /**
     * 支付
     * @param outTradeNo 订单号
     * @param price 支付价格
     * @param subject 标题
     * @param notifyUrl 回调地址
     * @param returnUrl 支付后返回地址
     * @return 结果
     * */
    PayResult pay(String outTradeNo, BigDecimal price, String subject, String notifyUrl, String returnUrl);

    /** 模拟渠道可读取受控结果；真实渠道默认复用原支付参数。 */
    default PayResult pay(PayDto dto) {
        return pay(dto.getOrderNumber(), dto.getPrice(), dto.getSubject(), dto.getNotifyUrl(), dto.getReturnUrl());
    }
    
    /**
     * 验签
     * @param params 参数
     * @return 结果
     * */
    boolean signVerify(Map<String, String> params);
    
    /**
     * 数据验证
     * @param params 参数
     * @param payBill 支付账单
     * @return 结果
     * */
    boolean dataVerify(Map<String, String> params, PayBill payBill);
    
    /**
     * 状态查询
     * @param outTradeNo 订单号
     * @return 结果
     * */
    TradeResult queryTrade(String outTradeNo);
    
    /** 所有渠道都必须显式消费稳定退款幂等号；缺少该实现时新渠道在编译期失败。 */
    RefundResult refund(String outTradeNo, BigDecimal price, String reason, String refundNo);
    
    /**
     * 支付渠道
     * @return 结果
     * */
    String getChannel();
}
