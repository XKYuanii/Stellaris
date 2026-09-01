package com.stellaris.controller;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.ProgramOperateDataDto;
import com.stellaris.dto.ReduceRemainNumberDto;
import com.stellaris.service.ProgramService;
import com.stellaris.service.reference.ReferenceReservationTransitionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目 控制层(内部rpc调用)
 * @author: xz_y
 **/
@RestController
@RequestMapping("/program/interior")
public class ProgramInteriorController {
    
    @Autowired
    private ProgramService programService;

    @Autowired
    private ReferenceReservationTransitionService referenceReservationTransitionService;
    
    
    @Operation(summary  = "扣减库存相关操作")
    @PostMapping(value = "/reduce/remain/number")
    public ApiResponse<Boolean> operateSeatLockAndTicketCategoryRemainNumber(@Valid @RequestBody ReduceRemainNumberDto reduceRemainNumberDto) {
        return ApiResponse.ok(programService.operateSeatLockAndTicketCategoryRemainNumber(reduceRemainNumberDto));
    }
    
    @Operation(summary = "支付或取消后的座位迁移")
    @PostMapping(value = "/reference/reservation/transition")
    public ApiResponse<Boolean> operateReferenceReservation(@Valid @RequestBody ProgramOperateDataDto dto) {
        return ApiResponse.ok(referenceReservationTransitionService.transition(dto));
    }
}
