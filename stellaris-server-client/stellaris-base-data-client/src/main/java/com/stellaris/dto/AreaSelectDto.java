package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 地区列表查询 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="AreaSelectDto", description ="AreaSelectDto")
public class AreaSelectDto {
    
    @Schema(name ="idList", type ="List<Long>",description = "id集合",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private List<Long> idList;
}
