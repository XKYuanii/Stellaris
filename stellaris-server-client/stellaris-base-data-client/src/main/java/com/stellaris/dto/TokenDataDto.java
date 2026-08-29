package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: token dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="TokenDataDto", description ="token数据")
public class TokenDataDto {
    
    @Schema(name ="name", type ="String", description ="名字",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String name;
    
    @Schema(name ="secret", type ="String", description ="秘钥",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String secret;
}
