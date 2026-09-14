package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.Order;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 mapper
 * @author: xz_y
 **/
public interface OrderMapper extends BaseMapper<Order> {

    /** User-facing ownership lookup; both keys are mandatory. */
    Order selectOwnedOrder(@Param("orderNumber") Long orderNumber, @Param("userId") Long userId);

    List<Order> selectExpiredForClose(@Param("now") Date now,
                                      @Param("orderStatus") Integer orderStatus,
                                      @Param("partitionCount") Integer partitionCount,
                                      @Param("partitionIndex") Integer partitionIndex,
                                      @Param("limit") Integer limit);

    int deferExpiryClose(@Param("id") Long id,
                         @Param("now") Date now,
                         @Param("nextRetryTime") Date nextRetryTime,
                         @Param("lastError") String lastError);
    
    /**
     * 真实删除订单数据
     * @return 结果
     * */
    Integer relDelOrder();
}
