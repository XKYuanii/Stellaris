package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.InsertMessageConsumerRecordDto;
import com.stellaris.dto.MessageIdDto;
import com.stellaris.dto.UpdateMessageConsumerRecordDto;
import com.stellaris.service.MessageConsumerRecordService;
import com.stellaris.vo.MessageConsumerRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息消费记录 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/message/consumer/record")
@Tag(name = "/message/consumer/record", description = "消息消费记录")
public class MessageConsumerRecordController {

    @Autowired
    private MessageConsumerRecordService messageConsumerRecordService;
    
    @Operation(summary  = "查询")
    @PostMapping(value = "/getByMessageId")
    public ApiResponse<MessageConsumerRecordVo> getMessageConsumerByMessageId(@Valid @RequestBody MessageIdDto messageIdDto) {
        return ApiResponse.ok(messageConsumerRecordService.getByMessageId(messageIdDto));
    }
    
    @Operation(summary  = "添加")
    @PostMapping(value = "/insert")
    public ApiResponse<MessageConsumerRecordVo> insertMessageConsumerRecord(@Valid @RequestBody InsertMessageConsumerRecordDto insertMessageConsumerRecordDto) {
        return ApiResponse.ok(messageConsumerRecordService.insertMessageConsumerRecord(insertMessageConsumerRecordDto));
    }
    
    @Operation(summary  = "更新消息消费记录")
    @PostMapping(value = "/update")
    public ApiResponse<Boolean> updateMessageConsumerRecord(@Valid @RequestBody UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto) {
        return ApiResponse.ok(messageConsumerRecordService.updateMessageConsumerRecord(updateMessageConsumerRecordDto));
    }
}
