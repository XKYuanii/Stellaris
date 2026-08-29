package com.stellaris.pay;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付结果
 * @author: 阿星不是程序员
 **/
@Data
@AllArgsConstructor
public class PayResult {
    
    private final boolean success;

    /** INITIATED/PAID/FAILED。 */
    private final String state;

    private final String body;

    private final String message;
}
