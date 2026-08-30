package com.stellaris.enums;

import lombok.Getter;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息发送状态枚举
 * @author: xz_y
 **/
@Getter
public enum MessageSendStatus {
    /**
     * 消息发送状态枚举
     * */
    UNSENT(1,"未发送"),
    SEND_FAIL(-1,"发送失败"),
    SEND_SUCCESS(2,"发送成功"),
    ;

    private final Integer code;

    private final String msg;

    MessageSendStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public static String getMsg(Integer code) {
        if (code == null) {
            return "";
        }
        for (MessageSendStatus re : MessageSendStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static MessageSendStatus getRc(Integer code) {
        for (MessageSendStatus re : MessageSendStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
