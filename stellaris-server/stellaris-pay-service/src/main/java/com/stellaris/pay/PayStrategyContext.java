package com.stellaris.pay;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付策略上下文
 * @author: 阿星不是程序员
 **/
public class PayStrategyContext {
    
    private final Map<String,PayStrategyHandler> payStrategyHandlerMap = new HashMap<>();
    
    public void put(String channel,PayStrategyHandler payStrategyHandler){
        payStrategyHandlerMap.put(channel,payStrategyHandler);
    }
    
    public PayStrategyHandler get(String channel){
        return Optional.ofNullable(payStrategyHandlerMap.get(channel)).orElseThrow(
                () -> new StellarisFrameException(BaseCode.PAY_STRATEGY_NOT_EXIST));
    }
}
