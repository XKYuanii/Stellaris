package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.security.CurrentRequestIdentity;
import com.stellaris.service.strategy.impl.ProgramOrderV5Strategy;
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
 * @description: 节目订单 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/program/order")
@Tag(name = "program-order", description = "节目订单")
public class ProgramOrderController {
    
    @Autowired
    private ProgramOrderV5Strategy programOrderV5Strategy;

    @Autowired
    private CurrentRequestIdentity currentRequestIdentity;
    
    @Operation(summary  = "购票：限流 + Redis Lua 原子预占 + Stream 异步落单")
    @PostMapping(value = "/create/v5")
    public ApiResponse<String> createV5(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto) {
        Long currentUserId = currentRequestIdentity.requireUserId();
        if (!currentUserId.equals(programOrderCreateDto.getUserId())) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请求用户与登录用户不一致");
        }
        programOrderCreateDto.setUserId(currentUserId);
        return ApiResponse.ok(programOrderV5Strategy.createOrder(programOrderCreateDto));
    }
}
