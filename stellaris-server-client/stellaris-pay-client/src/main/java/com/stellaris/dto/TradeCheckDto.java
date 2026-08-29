package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 交易状态 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="TradeCheckDto", description ="交易状态入参")
public class TradeCheckDto implements Serializable {
    
    @Schema(name ="outTradeNo", type ="String", description ="商户订单号", requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String outTradeNo;
    
    @Schema(name ="channel", type ="Integer", description ="支付渠道 alipay：支付宝 wx：微信",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String channel;
}
