package com.stellaris.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis和数据对账结果(精简优化)
 * @author: xz_y
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationSimpleResult {

    /**
     * 节目id
     * */
    private Long programId;

    /**
     * 对比结果
     * */
    private List<ExaminationIdentifierResult> examinationIdentifierResultList;

    
}
