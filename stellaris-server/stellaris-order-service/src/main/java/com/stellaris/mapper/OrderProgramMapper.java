package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.OrderProgram;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单节目 mapper
 * @author: 阿星不是程序员
 **/
public interface OrderProgramMapper extends BaseMapper<OrderProgram> {
    
    /**
     * 真实删除订单节目数据
     * @return 结果
     * */
    Integer relDelOrderProgram();
}
