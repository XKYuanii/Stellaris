package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.dto.TicketCategoryCountDto;
import com.stellaris.entity.TicketCategory;
import com.stellaris.entity.TicketCategoryAggregate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 票档 mapper
 * @author: 阿星不是程序员
 **/
public interface TicketCategoryMapper extends BaseMapper<TicketCategory> {
    
    /**
     * 票档统计
     * @param programIdList 参数
     * @return 结果
     * */
    List<TicketCategoryAggregate> selectAggregateList(@Param("programIdList")List<Long> programIdList);
    
    /**
     * 扣减数量
     * @param amount 数量
     * @param id 票档id
     * @param programId 节目id
     * @return 结果
     * */
    int reduceRemainNumber(@Param("amount")Long amount,
                           @Param("id")Long id,
                           @Param("programId") Long programId);
    
    /**
     * 增加数量
     * @param amount 数量
     * @param id 票档id
     * @param programId 节目id
     * @return 结果
     * */
    int increaseRemainNumber(@Param("amount")Long amount,
                           @Param("id")Long id,
                           @Param("programId") Long programId);
    
    /**
     * 批量更新数量
     * @param ticketCategoryCountDtoList 参数
     * @param programId 参数
     * @return 结果
     * */
    int batchUpdateRemainNumber(@Param("ticketCategoryCountDtoList") 
                                List<TicketCategoryCountDto> ticketCategoryCountDtoList,
                                @Param("programId")
                                Long programId);
}
