package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 所有规则 dto
 * @author: xz_y
 **/
@Data
@Schema(title="AllRuleDto", description ="全部规则")
public class AllRuleDto {
    
    @Schema(name ="ruleDto", type ="RuleDto", description ="普通规则", requiredMode= RequiredMode.REQUIRED)
    @NotNull
    private RuleDto ruleDto;
    
    @Schema(name ="depthRuleDtoList", type ="DepthRuleDto[]", description ="深度规则")
    private List<DepthRuleDto> depthRuleDtoList;
}
