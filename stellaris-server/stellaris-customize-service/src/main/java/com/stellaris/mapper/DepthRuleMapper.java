package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.DepthRule;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 深度规则 mapper
 * @author: 阿星不是程序员
 **/
public interface DepthRuleMapper extends BaseMapper<DepthRule> {
    
    /**
     * 删除所有规则
     * @return 结果
     * */
    int delAll();
}
