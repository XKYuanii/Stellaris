package com.stellaris.service.delaysend;

import com.alibaba.fastjson2.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.BusinessThreadPool;
import com.stellaris.client.ApiDataClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.context.DelayQueueContext;
import com.stellaris.core.SpringUtil;
import com.stellaris.dto.DelayOrderCancelDto;
import com.stellaris.dto.InsertMessageProducerRecordDto;
import com.stellaris.dto.UpdateMessageProducerRecordDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.MessageSendStatus;
import com.stellaris.enums.MessageType;
import com.stellaris.module.DelayOrderCancelMessageModule;
import com.stellaris.vo.MessageProducerRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.stellaris.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME;
import static com.stellaris.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT;
import static com.stellaris.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TOPIC;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟订单发送
 * @author: xz_y
 **/
@Slf4j
@Component
public class DelayOrderCancelSend {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private DelayQueueContext delayQueueContext;
    
    
    @Autowired
    private ApiDataClient apiDataClient;
    
    @Value("${delay.order.cancel:false}")
    private Boolean delayOrderCancel;
    
    public void sendMessage(DelayOrderCancelDto delayOrderCancelDto){
        if (!delayOrderCancel){
            return;
        }
        BusinessThreadPool.execute(() -> {
            Long messageTraceId = uidGenerator.getUid();
            Long messageId = uidGenerator.getUid();
            
            DelayOrderCancelMessageModule delayOrderCancelMessageModule = new DelayOrderCancelMessageModule();
            delayOrderCancelMessageModule.setMessageTraceId(messageTraceId);
            delayOrderCancelMessageModule.setMessageId(messageId);
            delayOrderCancelMessageModule.setProgramId(delayOrderCancelDto.getProgramId());
            delayOrderCancelMessageModule.setOrderNumber(delayOrderCancelDto.getOrderNumber());
            
            String messageContent = JSON.toJSONString(delayOrderCancelMessageModule);
            InsertMessageProducerRecordDto insertMessageProducerRecordDto = new InsertMessageProducerRecordDto();
            insertMessageProducerRecordDto.setMessageType(MessageType.DELAY_ORDER_CANCEL.getCode());
            insertMessageProducerRecordDto.setMessageTraceId(messageTraceId);
            insertMessageProducerRecordDto.setMessageBusinessesId(delayOrderCancelMessageModule.getProgramId());
            insertMessageProducerRecordDto.setMessageId(messageId);
            insertMessageProducerRecordDto.setMessageTopic(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC);
            insertMessageProducerRecordDto.setMessageContent(messageContent);
            ApiResponse<MessageProducerRecordVo> insertMessageProducerRecordApiResponse = apiDataClient.insertMessageProducerRecord(insertMessageProducerRecordDto);
            if (!insertMessageProducerRecordApiResponse.getCode().equals(BaseCode.SUCCESS.getCode())){
                log.error("添加记录消息发送日志失败，参数 : {}", JSON.toJSONString(insertMessageProducerRecordDto));
                return;
            }
            MessageProducerRecordVo messageProducerRecordVo = insertMessageProducerRecordApiResponse.getData();
            
            UpdateMessageProducerRecordDto updateMessageProducerRecordDto = new UpdateMessageProducerRecordDto();
            updateMessageProducerRecordDto.setId(messageProducerRecordVo.getId());
            
            try {
                log.info("延迟订单取消消息进行发送 消息体 : {}",messageContent);
                delayQueueContext.sendMessage(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC,
                        messageContent, DELAY_ORDER_CANCEL_TIME, DELAY_ORDER_CANCEL_TIME_UNIT);
                updateMessageProducerRecordDto.setMessageSendStatus(MessageSendStatus.SEND_SUCCESS.getCode());
            }catch (Exception e) {
                log.error("send message error message : {}",messageContent,e);
                updateMessageProducerRecordDto.setMessageSendStatus(MessageSendStatus.SEND_FAIL.getCode());
                updateMessageProducerRecordDto.setMessageSendException(e.getMessage());
            }
            apiDataClient.updateMessageProducerRecord(updateMessageProducerRecordDto);
        });
    }
}
