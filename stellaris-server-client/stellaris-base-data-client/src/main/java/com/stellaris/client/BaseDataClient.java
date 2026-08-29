package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.AreaGetDto;
import com.stellaris.dto.AreaSelectDto;
import com.stellaris.dto.GetChannelDataByCodeDto;
import com.stellaris.vo.AreaVo;
import com.stellaris.vo.GetChannelDataVo;
import com.stellaris.vo.TokenDataVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 基础数据服务 feign
 * @author: 阿星不是程序员
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"base-data-service",fallback  = BaseDataClientFallback.class)
public interface BaseDataClient {
    /**
     * 根据code查询数据
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping("/channel/data/getByCode")
    ApiResponse<GetChannelDataVo> getByCode(GetChannelDataByCodeDto dto);
    
    /**
     * 查询token数据
     * @return 结果
     * */
    @PostMapping(value = "/get")
    ApiResponse<TokenDataVo> get();
    
    /**
     * 根据id集合查询地区列表
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/area/selectByIdList")
    ApiResponse<List<AreaVo>> selectByIdList(AreaSelectDto dto);
    
    /**
     * 根据id查询地区
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/area/getById")
    ApiResponse<AreaVo> getById(AreaGetDto dto);
}
