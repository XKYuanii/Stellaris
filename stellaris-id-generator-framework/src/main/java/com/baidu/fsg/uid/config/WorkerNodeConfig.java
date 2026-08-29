package com.baidu.fsg.uid.config;

import com.baidu.fsg.uid.UidGenerator;
import com.baidu.fsg.uid.impl.CachedUidGenerator;
import com.baidu.fsg.uid.worker.WorkerIdAssigner;
import com.stellaris.toolkit.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 百度uid集成配置类
 * @author: 阿星不是程序员
 **/
@Configuration
public class WorkerNodeConfig {

//    @Bean("disposableWorkerIdAssigner")
//    @ConditionalOnMissingBean(WorkerIdAssigner.class)
//    public WorkerIdAssigner disposableWorkerIdAssigner(){
//        WorkerIdAssigner workerIdAssigner = new DisposableWorkerIdAssigner();
//        return workerIdAssigner;
//    }

    @Bean("cachedUidGenerator")
    public UidGenerator uidGenerator(WorkerIdAssigner disposableWorkerIdAssigner, SnowflakeIdGenerator snowflakeIdGenerator){
        CachedUidGenerator cachedUidGenerator = new CachedUidGenerator();
        cachedUidGenerator.setWorkerIdAssigner(disposableWorkerIdAssigner);
        cachedUidGenerator.setSnowflakeIdGenerator(snowflakeIdGenerator);
        return cachedUidGenerator;
    }
}