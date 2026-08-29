package com.stellaris.initialize.constant;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 初始化执行 不同策略类型
 * @author: 阿星不是程序员
 **/
public class InitializeHandlerType {
    
    public static final String APPLICATION_EVENT_LISTENER = "application_event_listener";
    
    public static final String APPLICATION_POST_CONSTRUCT = "application_post_construct";
    
    public static final String APPLICATION_INITIALIZING_BEAN = "application_initializing_bean";
    
    public static final String APPLICATION_COMMAND_LINE_RUNNER = "application_command_line_runner";
}
