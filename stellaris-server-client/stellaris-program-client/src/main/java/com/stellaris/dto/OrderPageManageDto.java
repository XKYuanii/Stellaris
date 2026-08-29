package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单查询 dto
 * @author: 阿星不是程序员
 **/
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(title="OrderPageManageDto", description ="订单")
public class OrderPageManageDto extends BasePageDto{
    
    @Schema(name ="programId", type ="Long", description ="id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
}
