package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.JobCallBackDto;
import com.stellaris.enums.BaseCode;
import org.springframework.stereotype.Component;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: job服务 feign 异常
 * @author: 阿星不是程序员
 **/
@Component
public class JobClientFallback implements JobClient {
    
    @Override
    public ApiResponse<Boolean> callBack(final JobCallBackDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
