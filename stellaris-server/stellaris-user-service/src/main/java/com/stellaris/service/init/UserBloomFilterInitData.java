package com.stellaris.service.init;

import cn.hutool.core.collection.CollectionUtil;
import com.stellaris.BusinessThreadPool;
import com.stellaris.handler.BloomFilterHandler;
import com.stellaris.initialize.base.AbstractApplicationPostConstructHandler;
import com.stellaris.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目种类缓存
 * @author: xz_y
 **/
@Component
public class UserBloomFilterInitData extends AbstractApplicationPostConstructHandler {
    
    @Autowired
    private BloomFilterHandler bloomFilterHandler;
    
    @Autowired
    private UserService userService;
    
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
    
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        BusinessThreadPool.execute(() -> {
            List<String> allMobile = userService.getAllMobile();
            if (CollectionUtil.isNotEmpty(allMobile)) {
                allMobile.forEach(mobile -> bloomFilterHandler.add(mobile));
            }
        });
    }
}
