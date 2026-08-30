package com.stellaris.enums;

import lombok.Getter;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息类型枚举
 * @author: xz_y
 **/
@Getter
public enum MessageType {
    /**
     * 消息类型枚举
     * */
    DELAY_ORDER_CANCEL(1,"延迟订单取消"),
    ;

    private final Integer code;

    private final String msg;

    MessageType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public static String getMsg(Integer code) {
        if (code == null) {
            return "";
        }
        for (MessageType re : MessageType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static MessageType getRc(Integer code) {
        for (MessageType re : MessageType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
