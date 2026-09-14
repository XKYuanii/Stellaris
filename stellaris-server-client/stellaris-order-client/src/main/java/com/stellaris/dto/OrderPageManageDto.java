package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Back-office order query parameters belong to the order-client contract. */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(title = "OrderPageManageDto", description = "订单")
public class OrderPageManageDto extends BasePageDto {
    @Schema(name = "programId", type = "Long", description = "id", requiredMode = RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
}
