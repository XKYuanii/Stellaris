package com.stellaris.service.trade;

import com.stellaris.dto.SeatInventoryInitializeDto;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.entity.TradeSeatInventory;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.enums.SellStatus;
import com.stellaris.mapper.TradeSeatInventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 开售前幂等发布交易库销售快照。 */
@Service
public class TradeInventoryService {
    private static final int INSERT_BATCH_SIZE = 500;
    private final TradeSeatInventoryMapper mapper;

    public TradeInventoryService(TradeSeatInventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean initialize(SeatInventoryInitializeDto dto) {
        validate(dto);
        long differentVersion = mapper.countDifferentSaleVersion(dto.getProgramId(), dto.getSaleVersion());
        if (differentVersion > 0) {
            throw new IllegalStateException("program already has a different published sale version");
        }
        List<SeatInventorySnapshotDto> seats = dto.getSeats();
        for (int start = 0; start < seats.size(); start += INSERT_BATCH_SIZE) {
            mapper.initializeSnapshot(dto.getProgramId(), dto.getSaleVersion(),
                    seats.subList(start, Math.min(start + INSERT_BATCH_SIZE, seats.size())));
        }
        long actual = mapper.countProgram(dto.getProgramId());
        if (actual != seats.size()) {
            throw new IllegalStateException("trade inventory size mismatch, expected=" + seats.size() + ", actual=" + actual);
        }
        return true;
    }

    public long available(long programId, long ticketCategoryId) {
        return mapper.countAvailable(programId, ticketCategoryId);
    }

    public List<SeatInventorySnapshotDto> current(long programId) {
        if (programId <= 0) throw new IllegalArgumentException("programId must be positive");
        return mapper.currentSnapshot(programId);
    }

    private void validate(SeatInventoryInitializeDto dto) {
        if (dto == null || dto.getProgramId() == null || dto.getProgramId() <= 0
                || dto.getSaleVersion() == null || dto.getSaleVersion().isBlank()
                || dto.getSeats() == null || dto.getSeats().isEmpty()) {
            throw new IllegalArgumentException("complete trade inventory snapshot is required");
        }
        Set<Long> seatIds = new HashSet<>();
        for (SeatInventorySnapshotDto seat : dto.getSeats()) {
            if (seat == null || seat.getSeatId() == null || seat.getTicketCategoryId() == null
                    || seat.getPriceInCents() == null || seat.getPriceInCents() < 0
                    || (!SellStatus.NO_SOLD.getCode().equals(seat.getSellStatus())
                    && !SellStatus.SOLD.getCode().equals(seat.getSellStatus()))) {
                throw new IllegalArgumentException("invalid seat inventory snapshot");
            }
            if (!seatIds.add(seat.getSeatId())) {
                throw new IllegalArgumentException("duplicate seat " + seat.getSeatId());
            }
        }
    }
}
