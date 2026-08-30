package com.stellaris.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis和数据对账结果(记录标识维度)
 * @author: xz_y
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationIdentifierResult {

    /**
     * 记录标识
     * */
    private String identifierId;

    /**
     * 用户id
     * */
    private String userId;
    
    /**
     * 记录类型的集合
     * */
    List<ExaminationRecordTypeResult> examinationRecordTypeResultList;
}
