package com.stellaris.service.delayconsumer;

import com.alibaba.fastjson.JSON;
import com.stellaris.client.ApiDataClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.core.ConsumerTask;
import com.stellaris.core.SpringUtil;
import com.stellaris.dto.InsertMessageConsumerRecordDto;
import com.stellaris.dto.MessageIdDto;
import com.stellaris.dto.OrderCancelDto;
import com.stellaris.dto.UpdateMessageConsumerRecordDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.MessageConsumerStatus;
import com.stellaris.enums.MessageType;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.module.DelayOrderCancelMessageModule;
import com.stellaris.service.OrderService;
import com.stellaris.util.DateUtils;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.MessageConsumerRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.stellaris.service.constant.OrderConstant.DELAY_ORDER_CANCEL_TOPIC;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 延迟订单取消
 * @author: xz_y
 **/
@Slf4j
@Component
public class DelayOrderCancelConsumer implements ConsumerTask {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private ApiDataClient apiDataClient;
    
    
    @Override
    public void execute(String content) {
        log.info("延迟订单取消消息进行消费 content : {}", content);
        if (StringUtil.isEmpty(content)) {
            log.error("延迟队列消息不存在");
            return;
        }
        DelayOrderCancelMessageModule delayOrderCancelMessageModule = JSON.parseObject(content, DelayOrderCancelMessageModule.class);
        
        Long messageTraceId = delayOrderCancelMessageModule.getMessageTraceId();
        Long messageId = delayOrderCancelMessageModule.getMessageId();
        Long programId = delayOrderCancelMessageModule.getProgramId();
        Long orderNumber = delayOrderCancelMessageModule.getOrderNumber();
        
        MessageIdDto messageIdDto = new MessageIdDto();
        messageIdDto.setMessageId(messageId);
        ApiResponse<MessageConsumerRecordVo> apiResponse = apiDataClient.getMessageConsumerByMessageId(messageIdDto);
        if (!apiResponse.getCode().equals(BaseCode.SUCCESS.getCode())) {
            log.error("查询消息消费记录失败 messageId : {}",messageId);
            return;
        }
        
        MessageConsumerRecordVo existMessageConsumerRecordVo = apiResponse.getData();
        
        if (Objects.nonNull(existMessageConsumerRecordVo) &&
                existMessageConsumerRecordVo.getMessageConsumerStatus().equals(MessageConsumerStatus.CONSUMER_SUCCESS.getCode())) {
            return;
        }
        Long messageConsumerRecordId = null;
        Integer messageConsumerCount;
        if (Objects.isNull(existMessageConsumerRecordVo)) {
            InsertMessageConsumerRecordDto insertMessageConsumerRecordDto = new InsertMessageConsumerRecordDto();
            insertMessageConsumerRecordDto.setMessageId(messageId);
            insertMessageConsumerRecordDto.setMessageTraceId(messageTraceId);
            insertMessageConsumerRecordDto.setMessageType(MessageType.DELAY_ORDER_CANCEL.getCode());
            insertMessageConsumerRecordDto.setMessageBusinessesId(programId);
            insertMessageConsumerRecordDto.setMessageTopic(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC);
            insertMessageConsumerRecordDto.setMessageContent(content);
            ApiResponse<MessageConsumerRecordVo> insertApiResponse = apiDataClient.insertMessageConsumerRecord(insertMessageConsumerRecordDto);
            if (!insertApiResponse.getCode().equals(BaseCode.SUCCESS.getCode())) {
                log.error("添加消息消费记录失败 insertMessageConsumerRecordDto : {}", JSON.toJSONString(insertMessageConsumerRecordDto));
                return;
            }
            MessageConsumerRecordVo saveMessageConsumerRecordVo = insertApiResponse.getData();
            messageConsumerRecordId = saveMessageConsumerRecordVo.getId();
            messageConsumerCount = saveMessageConsumerRecordVo.getMessageConsumerCount();
        }else {
            messageConsumerRecordId = existMessageConsumerRecordVo.getId();
            messageConsumerCount = existMessageConsumerRecordVo.getMessageConsumerCount() + 1;
        }
        UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto = new UpdateMessageConsumerRecordDto();
        updateMessageConsumerRecordDto.setId(messageConsumerRecordId);
        updateMessageConsumerRecordDto.setMessageConsumerCount(messageConsumerCount);
        updateMessageConsumerRecordDto.setConsumerTime(DateUtils.now());
        
        try {
            OrderCancelDto orderCancelDto = new OrderCancelDto();
            orderCancelDto.setOrderNumber(orderNumber);
            boolean cancel = orderService.cancel(orderCancelDto);
            if (cancel) {
                log.info("延迟订单取消成功 orderCancelDto : {}",content);
                updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_SUCCESS.getCode());
            }else {
                log.error("延迟订单取消失败 orderCancelDto : {}",content);
                updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_FAIL.getCode());
                updateMessageConsumerRecordDto.setMessageConsumerException("订单取消失败");
            }
        } catch (StellarisFrameException e) {
            // 创建订单可靠事件可能仍在积压；订单暂不存在不能视为取消成功，
            // 保留失败状态，让异常消息对账重新投递。
            updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_FAIL.getCode());
            updateMessageConsumerRecordDto.setMessageConsumerException(e.getMessage());
        } catch (Exception e) {
            updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_FAIL.getCode());
            updateMessageConsumerRecordDto.setMessageConsumerException(e.getMessage());
        }
        apiDataClient.updateMessageConsumerRecord(updateMessageConsumerRecordDto);
    }
    
    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC;
    }
}
