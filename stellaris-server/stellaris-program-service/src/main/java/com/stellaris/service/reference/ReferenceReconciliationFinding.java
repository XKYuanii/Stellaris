package com.stellaris.service.reference;

import lombok.Data;

/** 一条只读对账发现；执行器只报告或按状态机推进，不直接覆盖业务状态。 */
@Data
public class ReferenceReconciliationFinding {
    private String layer;
    private String code;
    private Long programId;
    private String intentId;
    private Long orderNumber;
    private String detail;

    public static ReferenceReconciliationFinding of(String layer, String code, Long programId,
                                                    String intentId, Long orderNumber, String detail) {
        ReferenceReconciliationFinding result = new ReferenceReconciliationFinding();
        result.layer = layer;
        result.code = code;
        result.programId = programId;
        result.intentId = intentId;
        result.orderNumber = orderNumber;
        result.detail = detail;
        return result;
    }
}
