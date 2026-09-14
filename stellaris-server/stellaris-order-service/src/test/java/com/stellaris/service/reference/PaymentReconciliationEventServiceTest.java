package com.stellaris.service.reference;

import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.entity.PaymentReconciliationEvent;
import com.stellaris.mapper.PaymentReconciliationEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReconciliationEventServiceTest {

    @Test
    void expectedBusinessWaitDoesNotIncrementFailureCount() {
        PaymentReconciliationEventMapper mapper = mock(PaymentReconciliationEventMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentReconciliationEventService service = new PaymentReconciliationEventService(
                mapper, mock(UidGenerator.class), registry);
        ReflectionTestUtils.setField(service, "retryBackoffMs", 10_000L);
        when(mapper.update(any(), any())).thenReturn(1);
        PaymentReconciliationEvent event = new PaymentReconciliationEvent();
        event.setId(1L);
        event.setOrderNumber(1001L);
        event.setEventStatus("PROCESSING");
        event.setRetryCount(2);

        service.waiting(event, "payment is still pending");

        ArgumentCaptor<PaymentReconciliationEvent> update =
                ArgumentCaptor.forClass(PaymentReconciliationEvent.class);
        verify(mapper).update(update.capture(), any());
        assertThat(update.getValue().getEventStatus()).isEqualTo("WAITING");
        assertThat(update.getValue().getRetryCount()).isEqualTo(2);
        assertThat(registry.counter("stellaris_payment_reconciliation_total", "result", "waiting").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("stellaris_payment_reconciliation_total", "result", "failed").count())
                .isZero();
    }
}
