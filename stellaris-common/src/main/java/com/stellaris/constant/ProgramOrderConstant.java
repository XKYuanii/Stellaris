package com.stellaris.constant;

import java.util.concurrent.TimeUnit;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 相关常量
 * @author: xz_y
 **/
public class ProgramOrderConstant {
    
    public static final String DELAY_ORDER_CANCEL_TOPIC ="d_delay_order_cancel_topic";
    
    public static final Long DELAY_ORDER_CANCEL_TIME = 2L;
    
    public static final TimeUnit DELAY_ORDER_CANCEL_TIME_UNIT = TimeUnit.MINUTES;
    
    /** 原始分库数量 */
    public static final int ORIGINAL_DATABASE_COUNT = 2;
    
    /** 原始分表数量 */
    public static final int ORIGINAL_TABLE_COUNT = 4;
    
    /** 虚拟分片总数（固定） */
    public static final int VIRTUAL_SHARD_COUNT = 1024;
}
