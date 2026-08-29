package com.stellaris.conf;

import com.stellaris.common.ApiResponse;
import lombok.Data;

import java.util.Map;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 临时信息
 * @author: 阿星不是程序员
 **/
@Data
public class RequestTemporaryWrapper {
    
    private Map<String,String> map;
    
    private ApiResponse<?> apiResponse;
}
