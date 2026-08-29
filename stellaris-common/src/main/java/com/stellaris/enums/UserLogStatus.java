package com.stellaris.enums;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户登录状态
 * @author: 阿星不是程序员
 **/
public enum UserLogStatus {
    /**
     * 用户登录状态
     * */
    IN(1,"登录"),
    
    OUT(0,"退出")
    ;

    private Integer code;

    private String msg;

    UserLogStatus(Integer code, String msg) {
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
        for (UserLogStatus re : UserLogStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static UserLogStatus getRc(Integer code) {
        for (UserLogStatus re : UserLogStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
