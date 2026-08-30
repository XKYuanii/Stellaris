package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 账单 dto
 * @author: xz_y
 **/
@Data
@Schema(title="PayDto", description ="支付")
public class PayBillDto implements Serializable {
    
    @Serial
    private static final long serialVersionUID = 1L;
    
    @Schema(name ="orderNumber", type ="Long", description ="订单号",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private String orderNumber;
}
