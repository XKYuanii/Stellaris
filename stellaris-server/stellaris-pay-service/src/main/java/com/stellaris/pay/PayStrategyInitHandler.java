package com.stellaris.pay;

import com.stellaris.initialize.base.AbstractApplicationInitializingBeanHandler;
import lombok.AllArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;
import java.util.Map.Entry;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付策略初始化
 * @author: xz_y
 **/
@AllArgsConstructor
public class PayStrategyInitHandler extends AbstractApplicationInitializingBeanHandler {
    
    private final PayStrategyContext payStrategyContext;
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
    
    @Override
    public void executeInit(ConfigurableApplicationContext context) {
        Map<String, PayStrategyHandler> payStrategyHandlerMap = context.getBeansOfType(PayStrategyHandler.class);
        for (Entry<String, PayStrategyHandler> entry : payStrategyHandlerMap.entrySet()) {
            PayStrategyHandler payStrategyHandler = entry.getValue();
            payStrategyContext.put(payStrategyHandler.getChannel(),payStrategyHandler);
        }
    }
}
