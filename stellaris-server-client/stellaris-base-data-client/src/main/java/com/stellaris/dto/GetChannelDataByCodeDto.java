package com.stellaris.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import lombok.Data;
    
import jakarta.validation.constraints.NotBlank;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 渠道数据查询 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="GetChannelDataByCodeDto", description ="渠道数据查询")
public class GetChannelDataByCodeDto {
    
    @Schema(name ="code", type ="String", description ="code码",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String code;
    
}