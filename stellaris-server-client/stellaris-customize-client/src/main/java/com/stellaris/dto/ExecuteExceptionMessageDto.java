package com.stellaris.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 实体
 * @author: 阿星不是程序员
 **/
@Data
public class ExecuteExceptionMessageDto {

    @NotNull
    private Long messageId;
}
