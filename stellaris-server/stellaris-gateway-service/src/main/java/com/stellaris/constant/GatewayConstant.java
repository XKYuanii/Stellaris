package com.stellaris.constant;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 常量
 * @author: xz_y
 **/
public class GatewayConstant {
    
    public static final String REQUEST_BODY = "body";
    
    public static final String ENCRYPT = "encrypt";
    
    public static final String CHARSET = "utf-8";
    
    public static final String BUSINESS_BODY = "businessBody";
    
    public static final String CODE = "code";
    
    public static final String TOKEN = "token";
    
    public static final String NO_VERIFY = "no_verify";
    
    public static final String VERIFY_VALUE = "true";
    
    public static final String USER_ID = "userId";

    /** 仅在显式开启的本机 demo 模式使用；网关消费后不会向下游透传。 */
    public static final String DEMO_USER_ID = "X-Stellaris-Demo-User-Id";

    /** 由网关从验签后的业务体提取，客户端同名头会被覆盖。 */
    public static final String PROGRAM_ID_HEADER = "X-Stellaris-Program-Id";
    
    public static final String V2 = "v2";
    
}
