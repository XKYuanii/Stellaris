package com.stellaris.service.reference;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** v5 事件、订单、库存三层对账的运行结果。 */
@Data
public class ReferenceReconciliationReport {
    private Date startedAt;
    private Date completedAt;
    private int inspectedIntents;
    private int inspectedEvents;
    private int inspectedOrders;
    private int inspectedPrograms;
    private int stateTransitions;
    private List<ReferenceReconciliationFinding> findings = new ArrayList<>();

    public void finding(String layer, String code, Long programId, String intentId, Long orderNumber, String detail) {
        findings.add(ReferenceReconciliationFinding.of(layer, code, programId, intentId, orderNumber, detail));
    }
}
