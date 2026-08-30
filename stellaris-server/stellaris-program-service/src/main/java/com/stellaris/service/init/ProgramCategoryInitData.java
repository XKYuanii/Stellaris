package com.stellaris.service.init;

import com.stellaris.BusinessThreadPool;
import com.stellaris.initialize.base.AbstractApplicationPostConstructHandler;
import com.stellaris.service.ProgramCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目种类缓存
 * @author: xz_y
 **/
@Component
public class ProgramCategoryInitData extends AbstractApplicationPostConstructHandler {
    
    @Autowired
    private ProgramCategoryService programCategoryService;
    
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
    
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        BusinessThreadPool.execute(() -> {
            programCategoryService.programCategoryRedisDataInit();
        });
    }
}
