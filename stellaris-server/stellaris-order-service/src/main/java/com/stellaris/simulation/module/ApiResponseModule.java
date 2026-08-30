package com.stellaris.simulation.module;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: ApiResponseModule
 * @author: xz_y
 **/
@Data
public class ApiResponseModule {

    @Schema(name ="code", type ="Integer", description ="响应码 0:成功 其余:失败")
    private Integer code;

    @Schema(name ="message", type ="String", description ="错误信息")
    private String message;
}
