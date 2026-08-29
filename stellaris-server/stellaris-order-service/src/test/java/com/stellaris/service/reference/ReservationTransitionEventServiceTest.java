package com.stellaris.service.reference;

import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.client.ProgramClient;
import com.stellaris.entity.ReservationTransitionEvent;
import com.stellaris.mapper.ReservationTransitionEventMapper;
import com.stellaris.dto.ProgramOperateDataDto;
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

class ReservationTransitionEventServiceTest {

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
                mock(ProgramClient.class), mock(UidGenerator.class), new SimpleMeterRegistry());
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
                mock(ProgramClient.class), mock(UidGenerator.class), new SimpleMeterRegistry());
        ProgramOperateDataDto opposite = new ProgramOperateDataDto();
        opposite.setProgramId(12L);
        opposite.setIntentId("intent-12");
        opposite.setSellStatus(3);

        assertThatIllegalStateException().isThrownBy(() -> service.enqueue(10002L, 8L, opposite))
                .withMessageContaining("different reservation transition command");
    }
}
