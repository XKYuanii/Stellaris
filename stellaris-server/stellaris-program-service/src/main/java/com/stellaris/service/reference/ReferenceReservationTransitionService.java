package com.stellaris.service.reference;

import com.stellaris.dto.ProgramOperateDataDto;
import com.stellaris.enums.SellStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** v5 支付/取消后将交易库终态幂等同步到 Redis。 */
@Service
public class ReferenceReservationTransitionService {
    private final ReferenceSeatReservationService reservationService;

    public ReferenceReservationTransitionService(ReferenceSeatReservationService reservationService) {
        this.reservationService = reservationService;
    }

    public boolean transition(ProgramOperateDataDto dto) {
        if (dto.getIntentId() == null || dto.getIntentId().isBlank()) {
            throw new IllegalArgumentException("reservation ownership token is required for v5 inventory transition");
        }
        if (Objects.equals(dto.getSellStatus(), SellStatus.SOLD.getCode())) {
            reservationService.confirmSale(dto.getProgramId(), dto.getIntentId());
            return true;
        }
        if (Objects.equals(dto.getSellStatus(), SellStatus.NO_SOLD.getCode())) {
            reservationService.release(dto.getProgramId(), dto.getIntentId(),
                    dto.getTicketCategoryCountDtoList() == null ? List.of()
                            : dto.getTicketCategoryCountDtoList().stream()
                                    .map(item -> item.getTicketCategoryId()).distinct().toList());
            return true;
        }
        throw new IllegalArgumentException("unsupported v5 inventory target status");
    }

}
