package com.stellaris.service.kafka;

import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.entity.OrderCreateDltRecord;
import com.stellaris.mapper.OrderCreateDltRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCreateDltServiceTest {

    @Test
    void malformedPayloadMustStillBePersistedAsRawAuditFact() {
        OrderCreateDltRecordMapper mapper = mock(OrderCreateDltRecordMapper.class);
        UidGenerator uidGenerator = mock(UidGenerator.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(99L);
        OrderCreateDltService service = new OrderCreateDltService(
                mapper, uidGenerator, kafkaTemplate, new SimpleMeterRegistry());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "stellaris-create-order.DLT", 1, 42L, "bad", "{not-json");

        service.record(record);

        ArgumentCaptor<OrderCreateDltRecord> inserted = ArgumentCaptor.forClass(OrderCreateDltRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRecordStatus()).isEqualTo("RAW_ONLY");
        assertThat(inserted.getValue().getPayload()).isEqualTo("{not-json");
        assertThat(inserted.getValue().getOrderNumber()).isPositive();
        assertThat(inserted.getValue().getUserId()).isEqualTo(inserted.getValue().getOrderNumber());
        assertThat(inserted.getValue().getExceptionMessage()).contains("payloadParse=");
    }
}
