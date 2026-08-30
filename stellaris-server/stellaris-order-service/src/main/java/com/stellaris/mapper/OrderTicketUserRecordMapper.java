package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.OrderTicketUserRecord;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 购票人订单记录 mapper
 * @author: xz_y
 **/
public interface OrderTicketUserRecordMapper extends BaseMapper<OrderTicketUserRecord> {
    
    /**
     * 真实删除购票人订单记录数据
     * @return 结果
     * */
    Integer relDelOrderTicketUserRecord();

}
