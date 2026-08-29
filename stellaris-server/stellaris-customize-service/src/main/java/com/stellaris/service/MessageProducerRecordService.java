package com.stellaris.service;


import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.dto.InsertMessageProducerRecordDto;
import com.stellaris.dto.UpdateMessageProducerRecordDto;
import com.stellaris.entity.MessageConsumerRecord;
import com.stellaris.entity.MessageProducerRecord;
import com.stellaris.enums.MessageSendStatus;
import com.stellaris.enums.ReconciliationStatus;
import com.stellaris.mapper.MessageConsumerRecordMapper;
import com.stellaris.mapper.MessageProducerRecordMapper;
import com.stellaris.util.DateUtils;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.MessageProducerRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息发送服务实现层
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class MessageProducerRecordService extends ServiceImpl<MessageProducerRecordMapper, MessageProducerRecord> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;
    
    @Autowired
    private MessageConsumerRecordMapper messageConsumerRecordMapper;
    
    
    public MessageProducerRecord getMessageProducerRecordByMessageId(Long messageId) {
        LambdaQueryWrapper<MessageProducerRecord> wrapper = Wrappers.lambdaQuery(MessageProducerRecord.class);
        wrapper.eq(MessageProducerRecord::getMessageId, messageId);
        return messageProducerRecordMapper.selectOne(wrapper);
    }
    
    
    @Transactional(rollbackFor = Exception.class)
    public MessageProducerRecordVo insertMessageProducerRecord(InsertMessageProducerRecordDto insertMessageProducerRecordDto){
        MessageProducerRecord messageProducerRecord = new MessageProducerRecord();
        BeanUtils.copyProperties(insertMessageProducerRecordDto, messageProducerRecord);
        messageProducerRecord.setId(uidGenerator.getUid());
        messageProducerRecord.setMessageType(insertMessageProducerRecordDto.getMessageType());
        messageProducerRecord.setMessageTraceId(insertMessageProducerRecordDto.getMessageTraceId());
        messageProducerRecord.setMessageBusinessesId(insertMessageProducerRecordDto.getMessageBusinessesId());
        messageProducerRecord.setMessageId(insertMessageProducerRecordDto.getMessageId());
        messageProducerRecord.setMessageTopic(insertMessageProducerRecordDto.getMessageTopic());
        messageProducerRecord.setMessageContent(insertMessageProducerRecordDto.getMessageContent());
        messageProducerRecord.setMessageSendStatus(MessageSendStatus.UNSENT.getCode());
        messageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        messageProducerRecord.setSendTime(DateUtils.now());
        messageProducerRecordMapper.insert(messageProducerRecord);
        MessageProducerRecordVo messageProducerRecordVo = new MessageProducerRecordVo();
        BeanUtils.copyProperties(messageProducerRecord, messageProducerRecordVo);
        return messageProducerRecordVo;
    }
    
    public Boolean updateMessageProducerRecord(UpdateMessageProducerRecordDto updateMessageProducerRecordDto) {
        MessageProducerRecord udpateMessageProducerRecord = new MessageProducerRecord();
        BeanUtils.copyProperties(updateMessageProducerRecordDto,udpateMessageProducerRecord);
        if (StringUtil.isEmpty(udpateMessageProducerRecord.getMessageContent())) {
            udpateMessageProducerRecord.setMessageContent(null);
        }
        if (StringUtil.isEmpty(udpateMessageProducerRecord.getMessageSendException())) {
            udpateMessageProducerRecord.setMessageSendException(null);
        }
        return updateById(udpateMessageProducerRecord);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void updateToReconciliationSuccess(MessageProducerRecord oldMessageProducerRecord, MessageConsumerRecord oldMessageConsumerRecord){
        MessageProducerRecord updateMessageProducerRecord = new MessageProducerRecord();
        updateMessageProducerRecord.setId(oldMessageProducerRecord.getId());
        updateMessageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_SUCCESS.getCode());
        updateById(updateMessageProducerRecord);
        MessageConsumerRecord updateMessageConsumerRecord = new MessageConsumerRecord();
        updateMessageConsumerRecord.setId(oldMessageConsumerRecord.getId());
        updateMessageConsumerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_SUCCESS.getCode());
        messageConsumerRecordMapper.updateById(updateMessageConsumerRecord);
    }
}
