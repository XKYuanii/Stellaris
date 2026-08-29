package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.Order;
import org.apache.ibatis.annotations.Param;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 mapper
 * @author: 阿星不是程序员
 **/
public interface OrderMapper extends BaseMapper<Order> {
    
    /**
     * 查询账户下购票人数量
     * @param userId 用户id
     * @param programId 节目id
     * @return 结果
     * */
    Integer accountOrderCount(@Param("userId")Long userId,@Param("programId")Long programId);
    
    /**
     * 真实删除订单数据
     * @return 结果
     * */
    Integer relDelOrder();
}
