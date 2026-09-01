package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramOrderCreateDto;
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
    
    @Operation(summary  = "购票：限流 + 有界 Lua/Redis Stream + Kafka")
    @PostMapping(value = "/create/v5")
    public ApiResponse<String> createV5(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto) {
        return ApiResponse.ok(programOrderV5Strategy.createOrder(programOrderCreateDto));
    }
}
