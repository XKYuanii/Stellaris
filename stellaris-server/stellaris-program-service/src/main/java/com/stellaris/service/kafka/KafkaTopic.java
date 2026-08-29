package com.stellaris.service.kafka;

import com.stellaris.core.SpringUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: kafka topic
 * @author: 阿星不是程序员
 **/
@Data
@Component
public class KafkaTopic {
    
    @Value("${spring.kafka.topic:default}")
    private String topic;

    public String fullTopicName() {
        return SpringUtil.getPrefixDistinctionName() + "-" + topic;
    }

}
