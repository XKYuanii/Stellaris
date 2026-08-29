package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目搜索 dto
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="ProgramSearchDto", description ="节目搜索")
public class ProgramSearchDto extends ProgramPageListDto{
    
    @Schema(name ="content", type ="String", description ="搜索内容")
    private String content;
}
