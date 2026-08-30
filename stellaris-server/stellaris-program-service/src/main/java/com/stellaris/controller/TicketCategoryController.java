package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.TicketCategoryAddDto;
import com.stellaris.dto.TicketCategoryDto;
import com.stellaris.dto.TicketCategoryListByProgramDto;
import com.stellaris.dto.TicketCategoryListDto;
import com.stellaris.service.TicketCategoryService;
import com.stellaris.vo.TicketCategoryDetailVo;
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
 * @description: 票档 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/ticket/category")
@Tag(name = "ticket-category", description = "票档")
public class TicketCategoryController {
    
    @Autowired
    private TicketCategoryService ticketCategoryService;
    
    
    @Operation(summary  = "添加")
    @PostMapping(value = "/add")
    public ApiResponse<Long> add(@Valid @RequestBody TicketCategoryAddDto ticketCategoryAddDto) {
        return ApiResponse.ok(ticketCategoryService.add(ticketCategoryAddDto));
    }
    
    @Operation(summary  = "查询详情")
    @PostMapping(value = "/detail")
    public ApiResponse<TicketCategoryDetailVo> detail(@Valid @RequestBody TicketCategoryDto ticketCategoryDto) {
        return ApiResponse.ok(ticketCategoryService.detail(ticketCategoryDto));
    }

    @Operation(summary  = "查询集合(programId和票档id集合查询)")
    @PostMapping(value = "/select/list")
    public ApiResponse<List<TicketCategoryDetailVo>> selectList(@Valid @RequestBody TicketCategoryListDto ticketCategoryDto) {
        return ApiResponse.ok(ticketCategoryService.selectList(ticketCategoryDto));
    }

    @Operation(summary  = "查询集合(programId查询)")
    @PostMapping(value = "/select/list/by/program")
    public ApiResponse<List<TicketCategoryDetailVo>> selectListByProgram(@Valid @RequestBody TicketCategoryListByProgramDto ticketCategoryListByProgramDto) {
        return ApiResponse.ok(ticketCategoryService.selectListByProgram(ticketCategoryListByProgramDto));
    }
}
