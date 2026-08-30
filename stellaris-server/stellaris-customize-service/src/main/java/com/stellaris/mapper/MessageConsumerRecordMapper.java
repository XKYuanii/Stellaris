package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.MessageConsumerRecord;
import org.apache.ibatis.annotations.Delete;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息消费记录 mapper
 * @author: xz_y
 **/
public interface MessageConsumerRecordMapper extends BaseMapper<MessageConsumerRecord> {
    
    /**
     * 删除所有记录 
     * @return Integer 结果
     * */
    @Delete("DELETE FROM d_message_consumer_record")
    Integer delete();
}
