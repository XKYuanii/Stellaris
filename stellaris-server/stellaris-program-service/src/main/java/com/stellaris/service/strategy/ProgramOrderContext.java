package com.stellaris.service.strategy;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单上下文
 * @author: xz_y
 **/
@Component
public class ProgramOrderContext {
    
    private final Map<String, ProgramOrderStrategy> strategies = new ConcurrentHashMap<>(8);
    
    private final List<ProgramOrderStrategy> programOrderStrategyList;

    public ProgramOrderContext(List<ProgramOrderStrategy> programOrderStrategyList) {
        this.programOrderStrategyList = programOrderStrategyList;
    }
    
    @PostConstruct
    public void init() {
        strategies.clear();
        for (ProgramOrderStrategy programOrderStrategy : programOrderStrategyList) {
            String version = programOrderStrategy.version();
            ProgramOrderStrategy previous = strategies.putIfAbsent(version, programOrderStrategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate program order strategy version '" + version
                        + "': " + previous.getClass().getName() + " and "
                        + programOrderStrategy.getClass().getName());
            }
        }
    }
    
    public ProgramOrderStrategy get(String version){
        return Optional.ofNullable(strategies.get(version)).orElseThrow(() -> 
                new StellarisFrameException(BaseCode.PROGRAM_ORDER_STRATEGY_NOT_EXIST));
    }
}
