package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;
import java.util.Set;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目对账记录任务 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="ProgramRecordTaskUpdateDto", description ="节目对账记录修改任务")
public class ProgramRecordTaskUpdateDto {
    
    @Schema(name ="createTimeSet", type ="Set<Date>", description ="创建时间",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Set<Date> createTimeSet;
    
    @Schema(name ="beforeHandleStatus", type ="Integer", description ="修改之前的处理状态 1:未处理 1:已处理",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer beforeHandleStatus;
    
    @Schema(name ="afterHandleStatus", type ="Integer", description ="修改之后的处理状态 1:未处理 1:已处理",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer afterHandleStatus;
}
