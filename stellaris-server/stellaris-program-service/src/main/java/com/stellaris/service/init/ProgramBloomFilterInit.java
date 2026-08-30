package com.stellaris.service.init;

import cn.hutool.core.collection.CollectionUtil;
import com.stellaris.handler.BloomFilterHandler;
import com.stellaris.initialize.base.AbstractApplicationPostConstructHandler;
import com.stellaris.service.ProgramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目id布隆过滤器初始化
 * @author: xz_y
 **/
@Component
public class ProgramBloomFilterInit extends AbstractApplicationPostConstructHandler {
    
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private BloomFilterHandler bloomFilterHandler;
    
    @Override
    public Integer executeOrder() {
        return 4;
    }
    
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        List<Long> allProgramIdList = programService.getAllProgramIdList();
        if (CollectionUtil.isEmpty(allProgramIdList)) {
            return;
        }
        allProgramIdList.forEach(programId -> bloomFilterHandler.add(String.valueOf(programId)));
    }
}
