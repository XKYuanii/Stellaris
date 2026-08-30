package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 账户下某个节目的订单数量 dto
 * @author: xz_y
 **/
@Data
@Schema(title="AccountOrderCountDto", description ="账户下某个节目的订单数量")
public class AccountOrderCountDto {
    
    @Schema(name ="userId", type ="Long", description ="用户id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long userId;
    
    @Schema(name ="programId", type ="Long", description ="节目id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
}
