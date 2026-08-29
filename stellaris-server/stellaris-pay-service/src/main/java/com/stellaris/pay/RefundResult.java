package com.stellaris.pay;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 退款结果
 * @author: 阿星不是程序员
 **/
@Data
@AllArgsConstructor
public class RefundResult {
    
    private final boolean success;
    
    private final String body;
    
    private final String message;
}
