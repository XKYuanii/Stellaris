package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目查询 dto
 * @author: xz_y
 **/
@Data
@Schema(title="ProgramManageDto", description ="节目")
public class ProgramManageDto {
    
    @Schema(name ="programId", type ="Long", description ="id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
}
