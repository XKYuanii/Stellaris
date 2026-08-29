package com.stellaris.service.reference;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.dto.ReferencePayStateQueryDto;
import com.stellaris.entity.PayBill;
import com.stellaris.mapper.PayBillMapper;
import com.stellaris.vo.ReferencePayStateVo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 仅向 v5 对账暴露支付账单事实，不调用渠道、不改变支付状态。 */
@Service
public class ReferencePayStateQueryService {
    private static final int MAX_BATCH_SIZE = 500;
    private final PayBillMapper payBillMapper;

    public ReferencePayStateQueryService(PayBillMapper payBillMapper) {
        this.payBillMapper = payBillMapper;
    }

    public List<ReferencePayStateVo> query(ReferencePayStateQueryDto dto) {
        List<String> orderNumbers = dto == null || dto.getOutOrderNos() == null ? List.of()
                : dto.getOutOrderNos().stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (orderNumbers.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("reference reconciliation batch exceeds " + MAX_BATCH_SIZE);
        }
        if (orderNumbers.isEmpty()) {
            return List.of();
        }
        return payBillMapper.selectList(Wrappers.lambdaQuery(PayBill.class)
                        .in(PayBill::getOutOrderNo, orderNumbers))
                .stream().map(this::toState).toList();
    }

    private ReferencePayStateVo toState(PayBill payBill) {
        ReferencePayStateVo state = new ReferencePayStateVo();
        state.setOutOrderNo(payBill.getOutOrderNo());
        state.setPayBillStatus(payBill.getPayBillStatus());
        state.setPayAmount(payBill.getPayAmount());
        return state;
    }
}
