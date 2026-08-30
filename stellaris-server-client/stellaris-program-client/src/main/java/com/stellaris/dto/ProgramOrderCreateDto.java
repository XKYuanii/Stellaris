package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目订单创建 dto
 * @author: xz_y
 **/
@Data
@Schema(title="ProgramOrderCreateDto", description ="节目订单创建")
public class ProgramOrderCreateDto {

    /**
     * 客户端一次提交的稳定幂等号。v5 必填，历史版本保持可选以兼容原接口。
     */
    @Schema(name ="requestId", type ="String", description ="v5 客户端幂等请求号")
    private String requestId;
    
    @Schema(name ="programId", type ="Long", description ="节目id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
    
    @Schema(name ="userId", type ="Long", description ="用户id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long userId;
    
    @Schema(name ="ticketUserIdList", type ="List<Long>", description ="购票人id集合",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private List<Long> ticketUserIdList;
    
    @Schema(name ="seatDtoList", type ="List<SeatDto>", description = "座位")
    private List<SeatDto> seatDtoList;
    
    @Schema(name ="ticketCategoryId", type ="Long", description = "节目票档id(如果不选座位，那么票档id必填)")
    private Long ticketCategoryId;
    
    @Schema(name ="ticketCount", type ="Integer", description = "购买票数量(如果不选座位，那么购买票数量必填)")
    private Integer ticketCount;

    /** 网关入参不可信，节目服务业务校验后覆盖；仅供 v5 Redis 原子限购。 */
    @Schema(hidden = true)
    private Integer serverAccountLimit;
}
