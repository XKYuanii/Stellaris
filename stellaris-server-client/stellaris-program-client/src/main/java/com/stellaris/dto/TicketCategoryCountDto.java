package com.stellaris.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 票据数据操作 dto
 * @author: xz_y
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketCategoryCountDto {
    
    /**
     * 票档id
     * */
    private Long ticketCategoryId;
    
    /**
     * 数量
     * */
    private Long count;
}
