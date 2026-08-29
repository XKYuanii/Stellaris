package com.stellaris.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramManageDto;
import com.stellaris.dto.SeatPageManageDto;
import com.stellaris.service.ProgramManageService;
import com.stellaris.vo.SeatManageVo;
import com.stellaris.vo.TicketCategoryDbManageVo;
import com.stellaris.vo.TicketCategoryDetailManageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目后台管理 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/program/manage")
@Tag(name = "program/manage", description = "节目后台管理")
public class ProgramManageController {
    
    
    @Autowired
    private ProgramManageService programManageService;
    
    @Operation(summary  = "查询节目票档信息集合")
    @PostMapping(value = "/ticket/category/list")
    public ApiResponse<List<TicketCategoryDetailManageVo>> ticketCategoryList(@Valid @RequestBody ProgramManageDto programManageDto) {
        return ApiResponse.ok(programManageService.ticketCategoryList(programManageDto));
    }
    
    @Operation(summary  = "查询数据库节目票档信息集合")
    @PostMapping(value = "/db/ticket/category/list")
    public ApiResponse<List<TicketCategoryDbManageVo>> dbTicketCategoryList(@Valid @RequestBody ProgramManageDto programManageDto) {
        return ApiResponse.ok(programManageService.dbTicketCategoryList(programManageDto));
    }
    
    @Operation(summary  = "查询节目座位信息集合")
    @PostMapping(value = "/seat/page")
    public ApiResponse<IPage<SeatManageVo>> seatPage(@Valid @RequestBody SeatPageManageDto seatPageManageDto) {
        return ApiResponse.ok(programManageService.seatPage(seatPageManageDto));
    }
}
