package com.stellaris.kafka;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: kafka topic
 * @author: 阿星不是程序员
 **/
@Data
public class KafkaTopic {
    
    @Value("${spring.kafka.topic:default}")
    private String topic;

}
