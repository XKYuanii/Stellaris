package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 消息id dto
 * @author: xz_y
 **/
@Data
@Schema(title="MessageIdDto", description ="消息id")
public class MessageIdDto {
    
    /**
     * 消息id
     */
    @Schema(name ="messageId", type ="Long", description ="消息id", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private Long messageId;
}
