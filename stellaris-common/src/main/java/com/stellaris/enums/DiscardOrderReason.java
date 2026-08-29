package com.stellaris.enums;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 废弃订单原因
 * @author: 阿星不是程序员
 **/
public enum DiscardOrderReason {
    /**
     * 废弃订单类型
     * */
    CONSUMER_DELAY(1,"mq消费消息延时"),
    
    MODIFY_PROGRAM_REMAIN_NUMBER_SEAT_FAIL(2,"修改节目服务的余票和座位失败"),
    
    CREATE_ORDER_FAIL(3,"创建订单失败"),
    ;

    private Integer code;

    private String msg;

    DiscardOrderReason(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public static String getMsg(Integer code) {
        for (DiscardOrderReason re : DiscardOrderReason.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static DiscardOrderReason getRc(Integer code) {
        for (DiscardOrderReason re : DiscardOrderReason.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
