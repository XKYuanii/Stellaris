package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AddApiDataDto;
import com.stellaris.dto.InsertMessageConsumerRecordDto;
import com.stellaris.dto.InsertMessageProducerRecordDto;
import com.stellaris.dto.MessageIdDto;
import com.stellaris.dto.UpdateMessageConsumerRecordDto;
import com.stellaris.dto.UpdateMessageProducerRecordDto;
import com.stellaris.vo.MessageConsumerRecordVo;
import com.stellaris.vo.MessageProducerRecordVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 定制服务 feign
 * @author: 阿星不是程序员
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"customize-service",fallback = ApiDataClientFallback.class)
public interface ApiDataClient {
    
    /**
     * 添加
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/apiData/add")
    ApiResponse<Boolean> add(AddApiDataDto dto);
    
    /**
     * 添加消息发送记录
     * @param insertMessageProducerRecordDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/message/producer/record/insert")
    ApiResponse<MessageProducerRecordVo> insertMessageProducerRecord(InsertMessageProducerRecordDto insertMessageProducerRecordDto);
    
    /**
     * 更新消息发送记录
     * @param updateMessageProducerRecordDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/message/producer/record/update")
    ApiResponse<Boolean> updateMessageProducerRecord(UpdateMessageProducerRecordDto updateMessageProducerRecordDto);
    
    /**
     * 查询消息消费记录
     * @param messageIdDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/message/consumer/record/getByMessageId")
    ApiResponse<MessageConsumerRecordVo> getMessageConsumerByMessageId(MessageIdDto messageIdDto);
    
    /**
     * 添加消息消费记录
     * @param insertMessageConsumerRecordDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/message/consumer/record/insert")
    ApiResponse<MessageConsumerRecordVo> insertMessageConsumerRecord(InsertMessageConsumerRecordDto insertMessageConsumerRecordDto);
    
    /**
     * 更新消息消费记录
     * @param updateMessageConsumerRecordDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/message/consumer/record/update")
    ApiResponse<Boolean> updateMessageConsumerRecord(UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto);
}
