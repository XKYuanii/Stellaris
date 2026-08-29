package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.InsertMessageProducerRecordDto;
import com.stellaris.dto.UpdateMessageProducerRecordDto;
import com.stellaris.service.MessageProducerRecordService;
import com.stellaris.vo.MessageProducerRecordVo;
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
 * @description: 消息发送记录 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/message/producer/record")
@Tag(name = "/message/producer/record", description = "消息发送记录")
public class MessageProducerRecordController {

    @Autowired
    private MessageProducerRecordService messageProducerRecordService;
    
    @Operation(summary  = "添加消息发送记录")
    @PostMapping(value = "/insert")
    public ApiResponse<MessageProducerRecordVo> insertMessageProducerRecord(@Valid @RequestBody InsertMessageProducerRecordDto insertMessageProducerRecordDto) {
        return ApiResponse.ok(messageProducerRecordService.insertMessageProducerRecord(insertMessageProducerRecordDto));
    }
    
    @Operation(summary  = "更新消息发送记录")
    @PostMapping(value = "/update")
    public ApiResponse<Boolean> updateMessageProducerRecord(@Valid @RequestBody UpdateMessageProducerRecordDto updateMessageProducerRecordDto) {
        return ApiResponse.ok(messageProducerRecordService.updateMessageProducerRecord(updateMessageProducerRecordDto));
    }
}
