package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单支付后状态检查 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="OrderPayCheckDto", description ="订单支付后状态检查")
public class OrderPayCheckDto {
    
    @Schema(name ="orderNumber", type ="String", description ="订单编号", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long orderNumber;
    
    @Schema(name ="payChannelType", type ="Integer", description ="支付方式 1.支付宝 2.微信 3.模拟支付", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer payChannelType;
}
