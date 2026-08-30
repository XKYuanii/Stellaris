package com.stellaris.handler;

import com.stellaris.enums.BaseCode;
import com.stellaris.enums.MessageType;
import com.stellaris.exception.StellarisFrameException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 异常消息处理上下文
 * @author: xz_y
 **/
@Component
public class ExceptionMessageHandlerContext {

    @Autowired
    private List<ExceptionMessageHandler> exceptionMessageHandlerList;
    
    private final Map<MessageType, ExceptionMessageHandler> exceptionMessageHandlerMap = new HashMap<>();
    
    @PostConstruct
    public void init() {
        for (ExceptionMessageHandler exceptionMessageHandler : exceptionMessageHandlerList) {
            exceptionMessageHandlerMap.put(exceptionMessageHandler.getMessageType(), exceptionMessageHandler);
        }
    }
    
    public ExceptionMessageHandler getExceptionMessageHandler(MessageType messageType) {
        return Optional.ofNullable(exceptionMessageHandlerMap.get(messageType)).orElseThrow(
                () -> new StellarisFrameException(BaseCode.MESSAGE_TYPE_NOT_EXIST));
    }
}
