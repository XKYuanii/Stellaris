package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户手机号更新 dto
 * @author: xz_y
 **/
@Data
@Schema(title="UserUpdateMobileDto", description ="修改用户手机号")
public class UserUpdateMobileDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    @Schema(name ="id", type ="Long", description ="用户id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long id;
    
    @Schema(name ="mobile", type ="String", description ="手机号",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String mobile;
    
}
