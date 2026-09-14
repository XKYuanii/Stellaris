package com.stellaris.service.trade;

import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.mapper.TradeSeatInventoryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeInventoryServiceTest {

    @Test
    void currentUsesOwnershipEnrichedAuthoritativeSnapshot() {
        TradeSeatInventoryMapper mapper = mock(TradeSeatInventoryMapper.class);
        TradeInventoryService service = new TradeInventoryService(mapper);
        SeatInventorySnapshotDto snapshot = new SeatInventorySnapshotDto();
        snapshot.setSeatId(11L);
        snapshot.setReservationId("intent-1");
        when(mapper.currentSnapshot(101L)).thenReturn(List.of(snapshot));

        assertThat(service.current(101L)).containsExactly(snapshot);
        verify(mapper).currentSnapshot(101L);
    }
}
