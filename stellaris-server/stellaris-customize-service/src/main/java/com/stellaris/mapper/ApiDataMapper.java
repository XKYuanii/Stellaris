package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stellaris.dto.ApiDataDto;
import com.stellaris.entity.ApiData;
import com.stellaris.vo.ApiDataVo;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: api调用记录 mapper
 * @author: 阿星不是程序员
 **/
public interface ApiDataMapper extends BaseMapper<ApiData> {
    /**
     * 分页查询
     * @param page 分页对象
     * @param apiDataDto 参数
     * @return 分页数据
     * */
    Page<ApiDataVo> pageList(Page<ApiData> page, ApiDataDto apiDataDto);
}
