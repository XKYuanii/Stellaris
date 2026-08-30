package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.DepthRuleDto;
import com.stellaris.dto.DepthRuleStatusDto;
import com.stellaris.dto.DepthRuleUpdateDto;
import com.stellaris.service.DepthRuleService;
import com.stellaris.vo.DepthRuleVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 深度规则 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/depthRule")
@Tag(name = "depthRule", description = "深度规则")
public class DepthRuleController {

    @Autowired
    private DepthRuleService depthRuleService;
    
    @Operation(summary  = "添加深度规则")
    @RequestMapping(value = "/add", method = RequestMethod.POST)
    public ApiResponse add(@Valid @RequestBody DepthRuleDto depthRuleDto) {
        depthRuleService.depthRuleAdd(depthRuleDto);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "修改深度规则")
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public ApiResponse update(@Valid @RequestBody DepthRuleUpdateDto depthRuleUpdateDto) {
        depthRuleService.depthRuleUpdate(depthRuleUpdateDto);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "修改深度规则状态")
    @RequestMapping(value = "/updateStatus", method = RequestMethod.POST)
    public ApiResponse updateStatus(@Valid @RequestBody DepthRuleStatusDto depthRuleStatusDto){
        depthRuleService.depthRuleUpdateStatus(depthRuleStatusDto);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "查询深度规则")
    @RequestMapping(value = "/get", method = RequestMethod.POST)
    public ApiResponse<List<DepthRuleVo>> get(){
        return ApiResponse.ok(depthRuleService.selectList());
    }
}
