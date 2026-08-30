package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.JobCallBackDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.validation.Valid;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: job服务 feign
 * @author: xz_y
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"job-service",fallback = JobClientFallback.class)
public interface JobClient {
    
    /**
     * 上报任务状态
     * @param dto 参数
     * @return 结果
     * */
    @RequestMapping(value = "jobRunRecord/callBack", method = RequestMethod.POST)
    ApiResponse<Boolean> callBack(@Valid @RequestBody JobCallBackDto dto);
}
