package com.stellaris.pro.ratelimit;

/** Redis 不可用时的行为。 */
public enum RateLimitFailPolicy {
    /** 使用本机令牌桶；仅适合允许小范围全局误差的查询接口。 */
    LOCAL,
    /** 放行；只适合非关键查询。 */
    OPEN,
    /** 拒绝；适合创建订单、支付等写接口。 */
    CLOSED
}
