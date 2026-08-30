package com.stellaris.module;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: DelayOrderCancelMessageModule
 * @author: xz_y
 **/
@Data
public class DelayOrderCancelMessageModule {

    private Long messageTraceId;
    
    private Long messageId;
    
    private Long programId;
    
    private Long orderNumber;
}
