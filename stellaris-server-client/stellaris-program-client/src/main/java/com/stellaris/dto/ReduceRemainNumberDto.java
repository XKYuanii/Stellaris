package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 扣减余票相关操作 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="ReduceRemainNumberDto", description ="扣减余票相关操作")
public class ReduceRemainNumberDto {

    @Schema(name ="orderNumber", type ="Long", description ="订单号/库存操作幂等键",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long orderNumber;

    @Schema(name ="eventId", type ="Long", description ="创建订单事件ID",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long eventId;

    @Schema(name ="intentId", type ="String", description ="v5 Redis 预订所有权标识")
    private String intentId;

    @Schema(name ="reservationExpireTime", type ="Date", description ="v5 Redis 预订截止时间")
    private Date reservationExpireTime;
    
    @Schema(name ="programId", type ="Long", description ="节目id",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long programId;
    
    @Schema(name ="ticketCategoryCountMap", type ="List<TicketCategoryCountDto>",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private List<TicketCategoryCountDto> ticketCategoryCountDtoList;
    
    @Schema(name ="seatIdList", type ="List<Long>", description ="座位id集合",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private List<Long> seatIdList;
    
    @Schema(name ="sellStatus", type ="Long", description ="座位状态",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer sellStatus;
}
