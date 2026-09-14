package com.stellaris.service.reference;

import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.entity.ReservationTransitionEvent;
import com.stellaris.mapper.ReservationTransitionEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ReservationTransitionEventServiceTest {

    @Test
    void relayAppliesTerminalStateDirectlyToRedisWriter() {
        ReservationTransitionEventMapper mapper = mock(ReservationTransitionEventMapper.class);
        OrderReservationReleaseService transition = mock(OrderReservationReleaseService.class);
        ReservationTransitionEvent event = new ReservationTransitionEvent();
        event.setId(12L);
        event.setOrderNumber(10003L);
        event.setUserId(9L);
        event.setProgramId(13L);
        event.setIntentId("intent-13");
        event.setTargetSellStatus(3);
        event.setEventStatus("PENDING");
        event.setRetryCount(0);
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(event));
        when(mapper.update(any(), any())).thenReturn(1, 1);
        ReservationTransitionEventService service = new ReservationTransitionEventService(mapper, transition,
                mock(UidGenerator.class), new SimpleMeterRegistry(), Runnable::run);
        ReflectionTestUtils.setField(service, "processingTimeoutMs", 60_000L);
        ReflectionTestUtils.setField(service, "batchSize", 100);

        service.relay();

        verify(transition).transition(13L, "intent-13", 3);
        ArgumentCaptor<ReservationTransitionEvent> updates =
                ArgumentCaptor.forClass(ReservationTransitionEvent.class);
        verify(mapper, times(2)).update(updates.capture(), any());
        assertThat(updates.getAllValues().get(1).getEventStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void processingLeaseExpiryReturnsCommandToRetryableState() {
        ReservationTransitionEventMapper mapper = mock(ReservationTransitionEventMapper.class);
        ReservationTransitionEvent stuck = new ReservationTransitionEvent();
        stuck.setId(11L);
        stuck.setOrderNumber(10001L);
        stuck.setUserId(7L);
        stuck.setEventStatus("PROCESSING");
        stuck.setEditTime(new Date(System.currentTimeMillis() - 120_000));
        when(mapper.selectList(any())).thenReturn(List.of(stuck), List.of());

        ReservationTransitionEventService service = new ReservationTransitionEventService(mapper,
                mock(OrderReservationReleaseService.class), mock(UidGenerator.class),
                new SimpleMeterRegistry(), Runnable::run);
        ReflectionTestUtils.setField(service, "processingTimeoutMs", 60_000L);
        ReflectionTestUtils.setField(service, "batchSize", 100);

        service.relay();

        ArgumentCaptor<ReservationTransitionEvent> update = ArgumentCaptor.forClass(ReservationTransitionEvent.class);
        verify(mapper, times(1)).update(update.capture(), any());
        assertThat(update.getValue().getEventStatus()).isEqualTo("FAILED");
        assertThat(update.getValue().getNextRetryTime()).isNotNull();
        assertThat(update.getValue().getLastError()).contains("same commandId");
    }

    @Test
    void deterministicRedisConflictStopsEvenWhenAutomaticDeadLetteringIsDisabled() {
        ReservationTransitionEventMapper mapper = mock(ReservationTransitionEventMapper.class);
        OrderReservationReleaseService transition = mock(OrderReservationReleaseService.class);
        ReservationTransitionEvent event = event();
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(event));
        when(mapper.update(any(), any())).thenReturn(1, 1);
        doThrow(new ReservationTransitionConflictException("OWNER_MISMATCH", "intent-13"))
                .when(transition).transition(13L, "intent-13", 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReservationTransitionEventService service = service(mapper, transition, registry);
        ReflectionTestUtils.setField(service, "maxAttempts", 0);

        service.relay();

        ArgumentCaptor<ReservationTransitionEvent> updates = ArgumentCaptor.forClass(ReservationTransitionEvent.class);
        verify(mapper, times(2)).update(updates.capture(), any());
        assertThat(updates.getAllValues().get(1).getEventStatus()).isEqualTo("DEAD");
        assertThat(registry.counter("stellaris_reservation_transition_total",
                "result", "dead").count()).isEqualTo(1.0);
        assertThat(registry.counter("stellaris_reservation_transition_conflict_total",
                "reason", "OWNER_MISMATCH").count()).isEqualTo(1.0);
    }

    @Test
    void infrastructureFailureRemainsRetryableWhenAttemptLimitIsDisabled() {
        ReservationTransitionEventMapper mapper = mock(ReservationTransitionEventMapper.class);
        OrderReservationReleaseService transition = mock(OrderReservationReleaseService.class);
        ReservationTransitionEvent event = event();
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(event));
        when(mapper.update(any(), any())).thenReturn(1, 1);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(transition).transition(13L, "intent-13", 3);
        ReservationTransitionEventService service = service(mapper, transition, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "maxAttempts", 0);

        service.relay();

        ArgumentCaptor<ReservationTransitionEvent> updates = ArgumentCaptor.forClass(ReservationTransitionEvent.class);
        verify(mapper, times(2)).update(updates.capture(), any());
        assertThat(updates.getAllValues().get(1).getEventStatus()).isEqualTo("FAILED");
    }

    @Test
    void existingCommandWithOppositeTargetMustBeRejected() {
        ReservationTransitionEventMapper mapper = mock(ReservationTransitionEventMapper.class);
        ReservationTransitionEvent existing = new ReservationTransitionEvent();
        existing.setOrderNumber(10002L);
        existing.setUserId(8L);
        existing.setProgramId(12L);
        existing.setIntentId("intent-12");
        existing.setTargetSellStatus(1);
        when(mapper.selectOne(any())).thenReturn(existing);
        ReservationTransitionEventService service = new ReservationTransitionEventService(mapper,
                mock(OrderReservationReleaseService.class), mock(UidGenerator.class),
                new SimpleMeterRegistry(), Runnable::run);

        assertThatIllegalStateException().isThrownBy(() ->
                        service.enqueue(10002L, 8L, 12L, "intent-12", 3))
                .withMessageContaining("different reservation transition command");
    }

    private ReservationTransitionEvent event() {
        ReservationTransitionEvent event = new ReservationTransitionEvent();
        event.setId(12L);
        event.setCommandId(99L);
        event.setOrderNumber(10003L);
        event.setUserId(9L);
        event.setProgramId(13L);
        event.setIntentId("intent-13");
        event.setTargetSellStatus(3);
        event.setEventStatus("PENDING");
        event.setRetryCount(0);
        return event;
    }

    private ReservationTransitionEventService service(ReservationTransitionEventMapper mapper,
                                                      OrderReservationReleaseService transition,
                                                      SimpleMeterRegistry registry) {
        ReservationTransitionEventService service = new ReservationTransitionEventService(mapper, transition,
                mock(UidGenerator.class), registry, Runnable::run);
        ReflectionTestUtils.setField(service, "processingTimeoutMs", 60_000L);
        ReflectionTestUtils.setField(service, "batchSize", 100);
        ReflectionTestUtils.setField(service, "retryBackoffMs", 5_000L);
        return service;
    }
}
