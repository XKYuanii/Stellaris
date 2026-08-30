package com.stellaris.handler.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.context.DelayQueueContext;
import com.stellaris.entity.MessageProducerRecord;
import com.stellaris.enums.MessageSendStatus;
import com.stellaris.enums.MessageType;
import com.stellaris.enums.ReconciliationStatus;
import com.stellaris.handler.ExceptionMessageHandler;
import com.stellaris.mapper.MessageProducerRecordMapper;
import com.stellaris.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.stellaris.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME;
import static com.stellaris.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟订单取消异常消息处理
 * @author: xz_y
 **/
@Slf4j
@Component
public class DelayOrderCancelExceptionMessageHandler implements ExceptionMessageHandler {
    
    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;
    
    @Autowired
    private DelayQueueContext delayQueueContext;
    
    @Override
    public List<MessageProducerRecord> noReconciliationMessageProducerRecordList() {
        Date date;
        switch (DELAY_ORDER_CANCEL_TIME_UNIT) {
            case MINUTES -> date = DateUtils.addMinute(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            case HOURS -> date = DateUtils.addHour(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            case DAYS -> date = DateUtils.addDay(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            default -> date = DateUtils.addSecond(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
        }
        //查询出所有未对账成功，并且发送时间小于 当前时间-延迟时间-10秒 的消息记录
        Wrapper<MessageProducerRecord> messageRecordWrapper = Wrappers.lambdaQuery(MessageProducerRecord.class)
                .eq(MessageProducerRecord::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode())
                .lt(MessageProducerRecord::getSendTime, DateUtils.addSecond(date, -10));
        return messageProducerRecordMapper.selectList(messageRecordWrapper);
    }
    
    @Override
    public Boolean handle(MessageProducerRecord messageProducerRecord) {
        String messageContent = messageProducerRecord.getMessageContent();
        MessageProducerRecord udpateMessageProducerRecord = new MessageProducerRecord();
        udpateMessageProducerRecord.setId(messageProducerRecord.getId());
        //因为是处理异常消息，所以这里直接设置为未对账
        udpateMessageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        try {
            log.info("延迟订单取消消息进行发送 消息体 : {}",messageContent);
            //这里是处理异常，所以延迟订单关闭就要立即消费
            delayQueueContext.sendMessage(messageProducerRecord.getMessageTopic(), messageContent, 1, TimeUnit.SECONDS);
            //发送成功，更新发送成功状态
            udpateMessageProducerRecord.setMessageSendStatus(MessageSendStatus.SEND_SUCCESS.getCode());
        }catch (Exception e) {
            log.error("send message error message : {}",messageContent,e);
            //发送失败，更新发送失败状态
            udpateMessageProducerRecord.setMessageSendStatus(MessageSendStatus.SEND_FAIL.getCode());
            udpateMessageProducerRecord.setMessageSendException(e.getMessage());
        }
        messageProducerRecordMapper.updateById(udpateMessageProducerRecord);
        return true;
    }
    
    @Override
    public MessageType getMessageType() {
        return MessageType.DELAY_ORDER_CANCEL;
    }
}
