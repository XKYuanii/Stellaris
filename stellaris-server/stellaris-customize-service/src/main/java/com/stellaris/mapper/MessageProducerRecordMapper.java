package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.MessageProducerRecord;
import org.apache.ibatis.annotations.Delete;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息发送记录 mapper
 * @author: xz_y
 **/
public interface MessageProducerRecordMapper extends BaseMapper<MessageProducerRecord> {
    
    /** 
     * 删除所有记录 
     * @return Integer 结果
     * */
    @Delete("DELETE FROM d_message_producer_record")
    Integer delete();
}
