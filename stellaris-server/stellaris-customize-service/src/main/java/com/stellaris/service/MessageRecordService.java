package com.stellaris.service;


import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellaris.dto.ExecuteExceptionMessageDto;
import com.stellaris.dto.MessageRecordDto;
import com.stellaris.entity.MessageConsumerRecord;
import com.stellaris.entity.MessageProducerRecord;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.MessageConsumerStatus;
import com.stellaris.enums.MessageSendStatus;
import com.stellaris.enums.MessageType;
import com.stellaris.enums.ReconciliationStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.handler.ExceptionMessageHandlerContext;
import com.stellaris.mapper.MessageConsumerRecordMapper;
import com.stellaris.mapper.MessageProducerRecordMapper;
import com.stellaris.page.PageUtil;
import com.stellaris.reconciliation.ReconciliationTask;
import com.stellaris.reconciliation.ReconciliationTaskQueue;
import com.stellaris.vo.MessageRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息记录实现层
 * @author: xz_y
 **/
@Slf4j
@Service
public class MessageRecordService {
    
    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;
    
    @Autowired
    private MessageConsumerRecordMapper messageConsumerRecordMapper;
    
    @Autowired
    private ExceptionMessageHandlerContext exceptionMessageHandlerContext;
    
    @Autowired
    private ReconciliationTaskQueue reconciliationTaskQueue;
    
    @Autowired
    private MessageProducerRecordService messageProducerRecordService;
    
    
    public IPage<MessageRecordVo> page(MessageRecordDto messageRecordDto) {
        IPage<MessageRecordVo> messageRecordVoPage = new Page<>(messageRecordDto.getPageNumber(), messageRecordDto.getPageSize());
        IPage<MessageProducerRecord> messageProducerRecordPage =
                messageProducerRecordMapper.selectPage(PageUtil.getPageParams(messageRecordDto.getPageNumber(),
                        messageRecordDto.getPageSize()), Wrappers.lambdaQuery(MessageProducerRecord.class)
                        .eq(MessageProducerRecord::getMessageBusinessesId, messageRecordDto.getMessageBusinessesId()));
        List<MessageProducerRecord> messageProducerRecordList = messageProducerRecordPage.getRecords();
        if (CollectionUtil.isEmpty(messageProducerRecordList)) {
            return messageRecordVoPage;
        }
        List<MessageConsumerRecord> messageConsumerRecordList = 
                messageConsumerRecordMapper.selectList(Wrappers.lambdaQuery(MessageConsumerRecord.class)
                        .in(MessageConsumerRecord::getMessageId, messageProducerRecordList.stream()
                                .map(MessageProducerRecord::getMessageId).toList()));
        Map<Long, MessageConsumerRecord> messageConsumerRecordMap = messageConsumerRecordList.stream().collect(
                Collectors.toMap(MessageConsumerRecord::getMessageId, v -> v, 
                        (v1, v2) -> v2));
        List<MessageRecordVo> messageRecordVoList = new ArrayList<>();
        for (MessageProducerRecord messageProducerRecord : messageProducerRecordList) {
            MessageRecordVo messageRecordVo = new MessageRecordVo();
            BeanUtils.copyProperties(messageProducerRecord, messageRecordVo);
            messageRecordVo.setMessageProducerRecordId(messageProducerRecord.getId());
            messageRecordVo.setMessageTypeName(MessageType.getMsg(messageProducerRecord.getMessageType()));
            messageRecordVo.setMessageSendStatusName(MessageSendStatus.getMsg(messageProducerRecord.getMessageSendStatus()));
            messageRecordVo.setReconciliationStatusName(ReconciliationStatus.getMsg(messageProducerRecord.getReconciliationStatus()));
            MessageConsumerRecord messageConsumerRecord = messageConsumerRecordMap.get(messageProducerRecord.getMessageId());
            if (Objects.nonNull(messageConsumerRecord)) {
                messageRecordVo.setMessageConsumerRecordId(messageConsumerRecord.getId());
                messageRecordVo.setMessageConsumerException(messageConsumerRecord.getMessageConsumerException());
                messageRecordVo.setMessageConsumerStatus(messageConsumerRecord.getMessageConsumerStatus());
                messageRecordVo.setMessageConsumerStatusName(MessageConsumerStatus.getMsg(messageConsumerRecord.getMessageConsumerStatus()));
                messageRecordVo.setMessageConsumerCount(messageConsumerRecord.getMessageConsumerCount());
                messageRecordVo.setConsumerTime(messageConsumerRecord.getConsumerTime());
            }
            messageRecordVoList.add(messageRecordVo);
        }
        BeanUtils.copyProperties(messageProducerRecordPage, messageRecordVoPage);
        messageRecordVoPage.setRecords(messageRecordVoList);
        return messageRecordVoPage;
    }
    
    public Boolean executeExceptionMessage(ExecuteExceptionMessageDto executeExceptionMessageDto) {
        LambdaQueryWrapper<MessageProducerRecord> wrapper = Wrappers.lambdaQuery(MessageProducerRecord.class);
        wrapper.eq(MessageProducerRecord::getMessageId, executeExceptionMessageDto.getMessageId());
        MessageProducerRecord existMessageProducerRecord = messageProducerRecordMapper.selectOne(wrapper);
        if (Objects.isNull(existMessageProducerRecord)) {
            throw new StellarisFrameException(BaseCode.MESSAGE_NOT_EXIST);
        }
        if (ReconciliationStatus.RECONCILIATION_SUCCESS.getCode().equals(existMessageProducerRecord.getReconciliationStatus())) {
            return true;
        }
        MessageType messageType = MessageType.getRc(existMessageProducerRecord.getMessageType());
        if (Objects.isNull(messageType)) {
            throw new StellarisFrameException(BaseCode.MESSAGE_TYPE_NOT_EXIST);
        }
        return exceptionMessageHandlerContext.getExceptionMessageHandler(messageType)
                .handle(existMessageProducerRecord);
    }
    
    public Boolean executeReconciliationTask() {
        log.info("执行消息记录的对账任务");
        for (MessageType messageType : MessageType.values()) {
            if (!exceptionMessageHandlerContext.supports(messageType)) {
                log.debug("跳过已下线且没有处理器的消息类型: {}", messageType);
                continue;
            }
            try {
                List<MessageProducerRecord> noReconciliationMessageProducerRecordList =
                        exceptionMessageHandlerContext.getExceptionMessageHandler(messageType).noReconciliationMessageProducerRecordList();
                if (CollectionUtil.isEmpty(noReconciliationMessageProducerRecordList)) {
                    continue;
                }
                Map<Long, MessageConsumerRecord> messageConsumerRecordMap = getMessageConsumerRecordMap(noReconciliationMessageProducerRecordList.stream()
                        .map(MessageProducerRecord::getMessageId).toList());
                
                for (MessageProducerRecord messageProducerRecord : noReconciliationMessageProducerRecordList){
                    MessageConsumerRecord messageConsumerRecord =
                            messageConsumerRecordMap.get(messageProducerRecord.getMessageId());
                    if (Objects.isNull(messageConsumerRecord) ||
                            messageConsumerRecord.getMessageConsumerStatus().equals(MessageConsumerStatus.UNCONSUMED.getCode()) ||
                            messageConsumerRecord.getMessageConsumerStatus().equals(MessageConsumerStatus.CONSUMER_FAIL.getCode())) {
                        ReconciliationTask reconciliationTask = () -> {
                            exceptionMessageHandlerContext.getExceptionMessageHandler(messageType).handle(messageProducerRecord);
                        };
                        reconciliationTaskQueue.putTask(reconciliationTask);
                    }else {
                        Integer messageSendStatus = messageProducerRecord.getMessageSendStatus();
                        Integer messageConsumerStatus = messageConsumerRecord.getMessageConsumerStatus();
                        if (messageSendStatus.equals(MessageSendStatus.SEND_SUCCESS.getCode()) &&
                                messageConsumerStatus.equals(MessageConsumerStatus.CONSUMER_SUCCESS.getCode())) {
                            messageProducerRecordService.updateToReconciliationSuccess(messageProducerRecord,messageConsumerRecord);
                        }
                    }
                }
            }catch (Exception e){
                log.error("executeReconciliationTask error",e);
            }
        }
        return true;
    }
    
    /**
     * 查询对应的消息消费记录
     * */
    public Map<Long, MessageConsumerRecord> getMessageConsumerRecordMap(List<Long> messageIdList){
        LambdaQueryWrapper<MessageConsumerRecord> messageConsumerRecordWrapper = Wrappers.lambdaQuery(MessageConsumerRecord.class);
        messageConsumerRecordWrapper.in(MessageConsumerRecord::getMessageId, messageIdList);
        List<MessageConsumerRecord> messageConsumerRecordList = messageConsumerRecordMapper.selectList(messageConsumerRecordWrapper);
        return messageConsumerRecordList.stream().collect(Collectors.toMap(MessageConsumerRecord::getMessageId, m -> m));
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void deleteMessageRecord(Date date){
        //把之前的消息记录数据删除掉，真实环境中不会删除的，这里是为了在线演示才删除的，要不然数据太多了
        messageProducerRecordMapper.delete();
        messageConsumerRecordMapper.delete();
    }
}
