package com.stellaris.enums;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 是否允许退款
 * @author: 阿星不是程序员
 **/
public enum PermitRefund {
    /**
     * 支付账单状态
     * */
    NO_REFUND(0,"不支持退"),
    CONDITIONAL_REFUND(1,"条件退"),
    FULL_REFUND(3,"全部退款"),
    ;

    private Integer code;

    private String msg;

    PermitRefund(Integer code, String msg) {
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
        for (PermitRefund re : PermitRefund.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static PermitRefund getRc(Integer code) {
        for (PermitRefund re : PermitRefund.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
