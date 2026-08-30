package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单查询 dto
 * @author: xz_y
 **/
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(title="SeatPageManageDto", description ="座位")
public class SeatPageManageDto extends BasePageDto{
    
    @Schema(name ="节目id", type ="Long", description ="id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
    
    @Schema(name ="票档id", type ="Long", description ="ticketCategoryId",requiredMode= RequiredMode.REQUIRED)
    private Long ticketCategoryId;
}
