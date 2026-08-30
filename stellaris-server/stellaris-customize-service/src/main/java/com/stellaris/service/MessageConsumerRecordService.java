package com.stellaris.service;


import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.dto.InsertMessageConsumerRecordDto;
import com.stellaris.dto.MessageIdDto;
import com.stellaris.dto.UpdateMessageConsumerRecordDto;
import com.stellaris.entity.MessageConsumerRecord;
import com.stellaris.enums.MessageConsumerStatus;
import com.stellaris.enums.ReconciliationStatus;
import com.stellaris.mapper.MessageConsumerRecordMapper;
import com.stellaris.util.DateUtils;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.MessageConsumerRecordVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息消费服务实现层
 * @author: xz_y
 **/
@Service
public class MessageConsumerRecordService extends ServiceImpl<MessageConsumerRecordMapper, MessageConsumerRecord> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private MessageConsumerRecordMapper messageConsumerRecordMapper;
    
    
    @Transactional(rollbackFor = Exception.class)
    public MessageConsumerRecordVo insertMessageConsumerRecord(InsertMessageConsumerRecordDto insertMessageConsumerRecordDto){
        MessageConsumerRecord messageConsumerRecord = new MessageConsumerRecord();
        messageConsumerRecord.setId(uidGenerator.getUid());
        messageConsumerRecord.setMessageType(insertMessageConsumerRecordDto.getMessageType());
        messageConsumerRecord.setMessageTraceId(insertMessageConsumerRecordDto.getMessageTraceId());
        messageConsumerRecord.setMessageBusinessesId(insertMessageConsumerRecordDto.getMessageBusinessesId());
        messageConsumerRecord.setMessageId(insertMessageConsumerRecordDto.getMessageId());
        messageConsumerRecord.setMessageTopic(insertMessageConsumerRecordDto.getMessageTopic());
        messageConsumerRecord.setMessageContent(insertMessageConsumerRecordDto.getMessageContent());
        messageConsumerRecord.setMessageConsumerStatus(MessageConsumerStatus.UNCONSUMED.getCode());
        messageConsumerRecord.setMessageConsumerCount(1);
        messageConsumerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        messageConsumerRecord.setConsumerTime(DateUtils.now());
        messageConsumerRecordMapper.insert(messageConsumerRecord);
        MessageConsumerRecordVo messageConsumerRecordVo = new MessageConsumerRecordVo();
        BeanUtils.copyProperties(messageConsumerRecord,messageConsumerRecordVo);
        return messageConsumerRecordVo;
    }
    
    public MessageConsumerRecord getMessageConsumerRecordByMessageId(Long messageId) {
        LambdaQueryWrapper<MessageConsumerRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageConsumerRecord::getMessageId, messageId);
        return messageConsumerRecordMapper.selectOne(wrapper);
    }
    
    public MessageConsumerRecordVo getByMessageId(MessageIdDto messageIdDto) {
        LambdaQueryWrapper<MessageConsumerRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageConsumerRecord::getMessageId, messageIdDto.getMessageId());
        MessageConsumerRecord messageConsumerRecord = messageConsumerRecordMapper.selectOne(wrapper);
        if (Objects.isNull(messageConsumerRecord)) {
            return null;
        }
        MessageConsumerRecordVo messageConsumerRecordVo = new MessageConsumerRecordVo();
        BeanUtils.copyProperties(messageConsumerRecord, messageConsumerRecordVo);
        return messageConsumerRecordVo;
    }
    
    public Boolean updateMessageConsumerRecord(UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto) {
        MessageConsumerRecord updateMessageConsumerRecord = new MessageConsumerRecord();
        BeanUtils.copyProperties(updateMessageConsumerRecordDto,updateMessageConsumerRecord);
        if (StringUtil.isEmpty(updateMessageConsumerRecord.getMessageContent())) {
            updateMessageConsumerRecord.setMessageContent(null);
        }
        if (StringUtil.isEmpty(updateMessageConsumerRecord.getMessageConsumerException())) {
            updateMessageConsumerRecord.setMessageConsumerException(null);
        }
        return updateById(updateMessageConsumerRecord);
    }
}
