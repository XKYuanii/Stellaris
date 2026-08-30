package com.stellaris.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 所有规则 vo
 * @author: xz_y
 **/
@Data
@Schema(title="AllDepthRuleVo", description ="全部规则")
public class AllDepthRuleVo {
    
    @Schema(name ="ruleDto", type ="RuleDto", description ="普通规则")
    private RuleVo ruleVo;
    
    @Schema(name ="depthRuleDtoList", type ="DepthRuleDto[]", description ="深度规则")
    private List<DepthRuleVo> depthRuleVoList;
}
