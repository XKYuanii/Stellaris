package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目数据操作 dto
 * @author: xz_y
 **/
@Data
@Schema(title="ProgramOperateDataDto", description ="节目数据操作")
public class ProgramOperateDataDto {

    @Schema(name ="intentId", type ="String", description ="Redis reservation 归属及幂等标识",requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private String intentId;
    
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
