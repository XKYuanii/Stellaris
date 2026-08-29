package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: job任务查询 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="JobInfoPageDto", description ="job任务查询")
public class JobInfoPageDto {
    
    @Schema(name ="pageSize", type ="Integer", description ="页码", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageSize;
    
    @Schema(name ="pageNo", type ="Integer", description ="页容量", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Integer pageNo;
}
