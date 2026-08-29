package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目对账记录任务 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="ProgramRecordTaskListDto", description ="节目对账记录查询任务")
public class ProgramRecordTaskListDto {
    
    @Schema(name ="createTime", type ="Date", description ="创建时间",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Date createTime;
    
    @Schema(name ="handleStatus", type ="Integer", description ="处理状态 1:未处理 1:已处理",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer handleStatus;

    @Schema(name ="limit", type ="Integer", description ="单批最大任务数")
    private Integer limit = 200;
}
