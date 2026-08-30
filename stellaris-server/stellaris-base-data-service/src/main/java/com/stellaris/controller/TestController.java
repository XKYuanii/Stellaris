package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ChannelDataAddDto;
import com.stellaris.service.ChannelDataService;
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
 * @description: 测试 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/test")
@Tag(name = "test-data", description = "测试")
public class TestController {
    
    @Autowired
    private ChannelDataService channelDataService;
    
    @Operation(summary = "测试")
    @PostMapping(value = "/test")
    public ApiResponse<Boolean> test(@Valid @RequestBody ChannelDataAddDto channelDataAddDto) {
        channelDataService.test(channelDataAddDto);
        return ApiResponse.ok(true);
    }
}
