package com.stellaris.service.properties;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单支付 回调地址配置
 * @author: xz_y
 **/
@Data
@Component
public class OrderProperties {

    /**
     * 支付成功后通知接口地址
     * */
    @Value("${orderPayNotifyUrl:http://localhost:6085/stellaris/order/order/alipay/notify}")
    private String orderPayNotifyUrl;
    
    /**
     * 支付成功后跳转页面
     * */
    @Value("${orderPayReturnUrl:http://localhost:5173/order/paySuccess}")
    private String orderPayReturnUrl;
}
