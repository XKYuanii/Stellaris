package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户手机号 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="UserMobileDto", description ="用户手机号入参")
public class UserMobileDto {
    
    @Schema(name ="name", type ="String", description ="用户手机号", requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String mobile;
}