package com.stellaris.service.reference;

import com.stellaris.dto.ProgramOperateDataDto;
import com.stellaris.enums.SellStatus;
import com.stellaris.service.ProgramService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** v5 支付/取消后的幂等库存迁移；不再依赖 MySQL Intent 状态机。 */
@Service
public class ReferenceReservationTransitionService {
    private final ProgramService programService;
    private final ReferenceSeatReservationService reservationService;

    public ReferenceReservationTransitionService(ProgramService programService,
                                                 ReferenceSeatReservationService reservationService) {
        this.programService = programService;
        this.reservationService = reservationService;
    }

    public boolean transition(ProgramOperateDataDto dto) {
        if (dto.getIntentId() == null || dto.getIntentId().isBlank()) {
            throw new IllegalArgumentException("reservation ownership token is required for v5 inventory transition");
        }
        // 该调用自身是本地事务且已做最终状态幂等；Redis 失败后重试不会重复返还余票。
        programService.operateProgramData(dto);
        if (Objects.equals(dto.getSellStatus(), SellStatus.SOLD.getCode())) {
            reservationService.confirmSale(dto.getProgramId(), dto.getIntentId());
            return true;
        }
        if (Objects.equals(dto.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
            reservationService.release(dto.getProgramId(), dto.getIntentId(),
                    dto.getTicketCategoryCountDtoList().stream().map(item -> item.getTicketCategoryId()).distinct().toList());
            return true;
        }
        throw new IllegalArgumentException("unsupported v5 inventory target status");
    }

}
