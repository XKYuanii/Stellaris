package com.stellaris.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(title = "PayResultVo", description = "统一支付结果")
public class PayResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** alipay/mock。 */
    private String channel;

    /** INITIATED/PAID/FAILED/PENDING。 */
    private String state;

    /** 支付宝表单等需要前端继续处理的渠道载荷。 */
    private String redirectPayload;

    private String message;
}
