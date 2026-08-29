package com.stellaris.enums;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单枚举
 * @author: 阿星不是程序员
 **/
public enum ProgramOrderVersion {
    /**
     * 历史实现只用于说明架构演进，不作为最终参考链路。
     * */
    V1_VERSION("v1", "v1版本", 1, Stage.EXPERIMENTAL),
    
    V2_VERSION("v2", "v2版本", 2, Stage.EXPERIMENTAL),
    
    V21_VERSION("v21", "v21版本", 21, Stage.EXPERIMENTAL),
   
    V3_VERSION("v3", "v3版本", 3, Stage.EXPERIMENTAL),
    
    V31_VERSION("v31", "v31版本", 31, Stage.EXPERIMENTAL),
    
    V4_VERSION("v4", "v4版本", 4, Stage.EXPERIMENTAL),
    
    V41_VERSION("v41", "v41版本", 41, Stage.EXPERIMENTAL),

    /** 最终面试参考链路；策略将在后续 v5/reference 批次注册。 */
    V5_REFERENCE("v5", "v5最终参考实现", 5, Stage.REFERENCE),
    ;

    private final String version;

    private final String msg;
    
    private final Integer value;

    private final Stage stage;

    ProgramOrderVersion(String version, String msg, Integer value, Stage stage) {
        this.version = version;
        this.msg = msg;
        this.value = value;
        this.stage = stage;
    }

    public String getVersion() {
        return version;
    }
    

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }
    
    public Integer getValue(){
        return value;
    }

    public Stage getStage() {
        return stage;
    }

    public boolean isExperimental() {
        return stage == Stage.EXPERIMENTAL;
    }
    

    public static String getMsg(String version) {
        for (ProgramOrderVersion re : ProgramOrderVersion.values()) {
            if (re.version.equals(version)) {
                return re.msg;
            }
        }
        return "";
    }

    public static ProgramOrderVersion getRc(String version) {
        for (ProgramOrderVersion re : ProgramOrderVersion.values()) {
            if (re.version.equals(version)) {
                return re;
            }
        }
        return null;
    }

    public enum Stage {
        EXPERIMENTAL,
        REFERENCE
    }
}
