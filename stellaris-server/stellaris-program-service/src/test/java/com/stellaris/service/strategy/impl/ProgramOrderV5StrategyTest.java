package com.stellaris.service.strategy.impl;

import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.dto.SeatDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.service.ProgramService;
import com.stellaris.service.composite.impl.ProgramDetailCheckHandler;
import com.stellaris.service.composite.impl.ProgramOrderCreateParamCheckHandler;
import com.stellaris.service.composite.impl.ProgramUserExistCheckHandler;
import com.stellaris.service.reference.ReferenceInventoryNotReadyException;
import com.stellaris.service.reference.ReferenceOrderOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgramOrderV5StrategyTest {

    @Test
    void coldInventoryFailsFastAndDeduplicatesBackgroundPreheat() {
        ReferenceOrderOrchestrator orchestrator = mock(ReferenceOrderOrchestrator.class);
        ProgramService programService = mock(ProgramService.class);
        ProgramOrderCreateParamCheckHandler paramCheck = mock(ProgramOrderCreateParamCheckHandler.class);
        ProgramDetailCheckHandler detailCheck = mock(ProgramDetailCheckHandler.class);
        ProgramUserExistCheckHandler userCheck = mock(ProgramUserExistCheckHandler.class);
        Queue<Runnable> queued = new ArrayDeque<>();
        Executor executor = queued::add;
        ProgramOrderV5Strategy strategy = new ProgramOrderV5Strategy(orchestrator, programService,
                paramCheck, detailCheck, userCheck, executor);
        ProgramOrderCreateDto request = selectedSeatRequest(101L);
        when(orchestrator.create(request)).thenThrow(new ReferenceInventoryNotReadyException(101L));

        assertThatThrownBy(() -> strategy.createOrder(request))
                .isInstanceOfSatisfying(ReferenceInventoryNotReadyException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo(BaseCode.INVENTORY_WARMING_UP.getCode()));
        assertThatThrownBy(() -> strategy.createOrder(request))
                .isInstanceOf(ReferenceInventoryNotReadyException.class);

        assertThat(queued).hasSize(1);
        verify(programService, never()).dataPreheat(org.mockito.ArgumentMatchers.any());

        queued.remove().run();

        verify(programService, times(1)).dataPreheat(org.mockito.ArgumentMatchers.argThat(
                preheat -> Long.valueOf(101L).equals(preheat.getProgramId())));
    }

    private static ProgramOrderCreateDto selectedSeatRequest(long programId) {
        ProgramOrderCreateDto request = new ProgramOrderCreateDto();
        request.setProgramId(programId);
        request.setUserId(7L);
        request.setTicketUserIdList(List.of(9L));
        SeatDto seat = new SeatDto();
        seat.setId(11L);
        request.setSeatDtoList(List.of(seat));
        return request;
    }
}
