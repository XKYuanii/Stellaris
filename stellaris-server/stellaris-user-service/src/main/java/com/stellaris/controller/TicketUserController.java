package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.TicketUserDto;
import com.stellaris.dto.TicketUserIdDto;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.security.CurrentRequestIdentity;
import com.stellaris.service.TicketUserService;
import com.stellaris.vo.TicketUserVo;
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
 * @description: 购票人 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/ticket/user")
@Tag(name = "ticket-user", description = "购票人")
public class TicketUserController {
    
    @Autowired
    private TicketUserService ticketUserService;

    @Autowired
    private CurrentRequestIdentity currentRequestIdentity;
    
    @Operation(summary  = "查询购票人列表")
    @PostMapping(value = "/list")
    public ApiResponse<List<TicketUserVo>> list(@Valid @RequestBody TicketUserListDto ticketUserListDto){
        Long currentUserId = requireMatchingUser(ticketUserListDto.getUserId());
        return ApiResponse.ok(ticketUserService.list(ticketUserListDto, currentUserId));
    }
    
    @Operation(summary  = "添加购票人")
    @PostMapping(value = "/add")
    public ApiResponse<Void> add(@Valid @RequestBody TicketUserDto ticketUserDto){
        Long currentUserId = requireMatchingUser(ticketUserDto.getUserId());
        ticketUserService.add(ticketUserDto, currentUserId);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "删除购票人")
    @PostMapping(value = "/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody TicketUserIdDto ticketUserIdDto){
        ticketUserService.delete(ticketUserIdDto, currentRequestIdentity.requireUserId());
        return ApiResponse.ok();
    }

    private Long requireMatchingUser(Long requestedUserId) {
        Long currentUserId = currentRequestIdentity.requireUserId();
        if (!currentUserId.equals(requestedUserId)) {
            throw new StellarisFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                    "请求用户与登录用户不一致");
        }
        return currentUserId;
    }
}
