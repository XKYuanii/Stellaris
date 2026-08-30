package com.stellaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目推荐列表查询 dto
 * @author: xz_y
 **/
@Data
@Schema(title="ProgramRecommendListDto", description ="节目推荐列表")
public class ProgramRecommendListDto {
    
    @Schema(name ="areaId", type ="Long", description ="所在区域id")
    private Long areaId;
    
    @Schema(name ="parentProgramCategoryId", type ="Long", description ="父节目类型id")
    private Long parentProgramCategoryId;
    
    @Schema(name ="programId", type ="Long", description ="查看节目详情时，调用推荐列表时要传入此节目id")
    private Long programId;
}
