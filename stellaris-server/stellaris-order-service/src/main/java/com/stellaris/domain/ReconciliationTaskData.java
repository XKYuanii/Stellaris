package com.stellaris.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 需要进行添加的数据
 * @author: 阿星不是程序员
 **/
@Data
@Schema(title="ReconciliationTaskData", description ="需要进行添加的数据")
public class ReconciliationTaskData {
    
    @Schema(name ="programId", type ="Long", description ="节目id")
    private Long programId;
    
    @Schema(name ="addRedisRecordData", type ="Map", description ="需要向redis添加的数据")
    private Map<String, ProgramRecord> addRedisRecordData;
    
}
