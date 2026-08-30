package com.stellaris.initialize.base;

import org.springframework.boot.CommandLineRunner;

import static com.stellaris.initialize.constant.InitializeHandlerType.APPLICATION_COMMAND_LINE_RUNNER;
import static com.stellaris.initialize.constant.InitializeHandlerType.APPLICATION_POST_CONSTRUCT;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用于处理 {@link CommandLineRunner} 类型 初始化执行 抽象
 * @author: xz_y
 **/
public abstract class AbstractApplicationCommandLineRunnerHandler implements InitializeHandler {
    
    @Override
    public String type() {
        return APPLICATION_COMMAND_LINE_RUNNER;
    }
}
