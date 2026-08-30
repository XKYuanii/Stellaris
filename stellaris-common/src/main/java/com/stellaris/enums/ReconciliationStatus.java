package com.stellaris.enums;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 对账状态
 * @author: xz_y
 **/
public enum ReconciliationStatus {
    /**
     * 对账状态
     * */
    RECONCILIATION_NO(1, "未对账"),
    RECONCILIATION_FAIL(-1,"对账完成有问题"),
    RECONCILIATION_SUCCESS(2,"对账完成没有问题"),
    RECONCILIATION_FINISH(3,"对账有问题处理完毕");
    
    private Integer code;
    
    private String msg = "";
    
    ReconciliationStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public Integer getCode() {
        return this.code;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public static String getMsg(Integer code) {
        for (ReconciliationStatus re : ReconciliationStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }
    
    public static ReconciliationStatus getRc(Integer code) {
        for (ReconciliationStatus re : ReconciliationStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
