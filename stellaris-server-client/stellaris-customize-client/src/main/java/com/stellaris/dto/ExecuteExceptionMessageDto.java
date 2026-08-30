package com.stellaris.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 实体
 * @author: xz_y
 **/
@Data
public class ExecuteExceptionMessageDto {

    @NotNull
    private Long messageId;
}
