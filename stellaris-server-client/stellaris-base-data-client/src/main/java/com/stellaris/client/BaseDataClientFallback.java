package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AreaGetDto;
import com.stellaris.dto.AreaSelectDto;
import com.stellaris.dto.GetChannelDataByCodeDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.vo.AreaVo;
import com.stellaris.vo.GetChannelDataVo;
import com.stellaris.vo.TokenDataVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户服务 feign 异常
 * @author: 阿星不是程序员
 **/
@Component
public class BaseDataClientFallback implements BaseDataClient{
    @Override
    public ApiResponse<GetChannelDataVo> getByCode(final GetChannelDataByCodeDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<TokenDataVo> get() {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<List<AreaVo>> selectByIdList(final AreaSelectDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<AreaVo> getById(final AreaGetDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
