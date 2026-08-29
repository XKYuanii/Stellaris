package com.stellaris.constant;

import com.stellaris.enums.RecordType;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 常量
 * @author: 阿星不是程序员
 **/
public class Constant {
    
    /**
     * 链路id
     * */
    public static final String TRACE_ID = "traceId";
    
    public static final String GRAY_FLAG_TRUE = "true";
    
    public static final String GRAY_FLAG_FALSE = "false";
    
    public static final String GRAY_PARAMETER = "gray";
    
    public static final String CODE = "code";
    
    public static final String USER_ID = "userId";
    
    public static final String GLIDE_LINE = "_";
    
    public static final String ALIPAY_NOTIFY_SUCCESS_RESULT = "success";
    
    public static final String ALIPAY_NOTIFY_FAILURE_RESULT = "failure";
    
    public static final String PREFIX_DISTINCTION_NAME = "prefix.distinction.name";
    
    public static final String DEFAULT_PREFIX_DISTINCTION_NAME = "stellaris";
    
    public static final String SPRING_INJECT_PREFIX_DISTINCTION_NAME = "${"+PREFIX_DISTINCTION_NAME+":"+DEFAULT_PREFIX_DISTINCTION_NAME+"}";
    
    public static final String SERVER_GRAY = "${spring.cloud.nacos.discovery.metadata.gray:false}";

    public static final String REDUCE = RecordType.REDUCE.getValue();
    
    public static final String CHANGE_STATUS = RecordType.CHANGE_STATUS.getValue();
    
    public static final String INCREASE = RecordType.INCREASE.getValue();
    
    public static final String SKY_WALKING_TRACE_ID = "skyWalkingTraceId";
    
}
