package com.stellaris.conf;

import com.stellaris.common.ApiResponse;
import lombok.Data;

import java.util.Map;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 临时信息
 * @author: xz_y
 **/
@Data
public class RequestTemporaryWrapper {
    
    private Map<String,String> map;
    
    private ApiResponse<?> apiResponse;
}
