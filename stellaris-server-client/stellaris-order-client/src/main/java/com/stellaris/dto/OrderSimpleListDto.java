package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单列表查询 dto
 * @author: xz_y
 **/
@Data
@Schema(title="OrderListDto", description ="订单列表查询")
public class OrderSimpleListDto {
    
    @Schema(name ="orderNumber", type ="Long", description ="订单编号")
    private Long orderNumber;
    
    @Schema(name ="userId", type ="Long", description ="用户id")
    private Long userId;
    
}
