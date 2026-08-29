package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目类型 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="ProgramCategoryDto", description ="节目类型")
public class ProgramCategoryDto {
    
    @Schema(name ="type", type ="Integer", description ="1:一级种类 2:二级种类", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer type;
}
