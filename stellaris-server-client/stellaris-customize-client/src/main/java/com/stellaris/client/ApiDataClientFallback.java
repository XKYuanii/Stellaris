package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AddApiDataDto;
import com.stellaris.dto.InsertMessageConsumerRecordDto;
import com.stellaris.dto.InsertMessageProducerRecordDto;
import com.stellaris.dto.MessageIdDto;
import com.stellaris.dto.UpdateMessageConsumerRecordDto;
import com.stellaris.dto.UpdateMessageProducerRecordDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.vo.MessageConsumerRecordVo;
import com.stellaris.vo.MessageProducerRecordVo;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 定制服务 feign 异常
 * @author: 阿星不是程序员
 **/
@Component
public class ApiDataClientFallback implements ApiDataClient {
    
    @Override
    public ApiResponse<Boolean> add(final AddApiDataDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<MessageProducerRecordVo> insertMessageProducerRecord(final InsertMessageProducerRecordDto insertMessageProducerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<Boolean> updateMessageProducerRecord(final UpdateMessageProducerRecordDto updateMessageProducerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<MessageConsumerRecordVo> getMessageConsumerByMessageId(final MessageIdDto messageIdDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<MessageConsumerRecordVo> insertMessageConsumerRecord(final InsertMessageConsumerRecordDto insertMessageConsumerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<Boolean> updateMessageConsumerRecord(final UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
