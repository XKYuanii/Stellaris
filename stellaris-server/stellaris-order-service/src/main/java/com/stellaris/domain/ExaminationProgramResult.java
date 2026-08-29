package com.stellaris.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis和数据对账结果(节目维度)
 * @author: 阿星不是程序员
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationProgramResult {
    
    /**
     * 记录标识的集合
     * */
    private List<ExaminationIdentifierResult> examinationIdentifierResultList;
}
