package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.entity.OrderTicketUserAggregate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 购票人订单 mapper
 * @author: 阿星不是程序员
 **/
public interface OrderTicketUserMapper extends BaseMapper<OrderTicketUser> {
    
    /**
     * 查询订单下购票人数量
     * @param orderNumberList 参数
     * @return 结果
     * */
    List<OrderTicketUserAggregate> selectOrderTicketUserAggregate(@Param("orderNumberList")List<Long> orderNumberList);
    
    /**
     * 真实删除购票人订单数据
     * @return 结果
     * */
    Integer relDelOrderTicketUser();

}
