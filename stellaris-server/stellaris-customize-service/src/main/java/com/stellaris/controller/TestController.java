package com.stellaris.controller;

import com.alibaba.fastjson.JSON;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.TestDto;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 测试 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/test")
@Slf4j
public class TestController {
    
    @Operation(summary  = "添加普通规则")
    @RequestMapping(value = "/test", method = RequestMethod.POST)
    public ApiResponse<Boolean> test(@Valid @RequestBody TestDto testDto) {
        log.info("dto : {}", JSON.toJSONString(testDto));
        return ApiResponse.ok(true);
    }
}
