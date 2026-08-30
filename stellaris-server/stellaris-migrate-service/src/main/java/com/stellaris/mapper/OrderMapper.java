package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.Order;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 mapper
 * @author: xz_y
 **/
public interface OrderMapper extends BaseMapper<Order> {
    
    /**
     * 物理删除订单 
     * @param ids 订单 id 列表
     * @return Integer 结果
     * */
    Integer physicalDeleteByIds(@Param("ids") List<Long> ids);
}
