package com.stellaris.service.kafka;

import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.domain.OrderCreateMq;
import com.stellaris.entity.OrderCreateDltRecord;
import com.stellaris.mapper.OrderCreateDltRecordMapper;
import com.stellaris.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class OrderCreateDltService {
    private final OrderCreateDltRecordMapper mapper;
    private final UidGenerator uidGenerator;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public OrderCreateDltService(OrderCreateDltRecordMapper mapper, UidGenerator uidGenerator,
                                 KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        this.mapper = mapper; this.uidGenerator = uidGenerator; this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(rollbackFor = Exception.class)
    public void record(ConsumerRecord<String, String> record) {
        OrderCreateDltRecord existing = mapper.selectOne(Wrappers.lambdaQuery(OrderCreateDltRecord.class)
                .eq(OrderCreateDltRecord::getDltTopic, record.topic())
                .eq(OrderCreateDltRecord::getSourcePartition, record.partition())
                .eq(OrderCreateDltRecord::getSourceOffset, record.offset()));
        if (existing != null) return;
        OrderCreateMq message = null;
        String parseError = null;
        try {
            message = JSON.parseObject(record.value(), OrderCreateMq.class);
            if (message == null || message.getOrderNumber() == null || message.getUserId() == null
                    || message.getProgramId() == null) {
                throw new IllegalArgumentException("required routing fields are missing");
            }
        } catch (RuntimeException ex) {
            parseError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        Date now = DateUtils.now();
        long fallbackRoute = fallbackRoute(record);
        OrderCreateDltRecord item = new OrderCreateDltRecord();
        item.setId(uidGenerator.getUid()); item.setEventId(message == null ? null : message.getEventId());
        item.setOrderNumber(parseError == null ? message.getOrderNumber() : fallbackRoute);
        item.setUserId(parseError == null ? message.getUserId() : fallbackRoute);
        item.setProgramId(parseError == null ? message.getProgramId() : 0L); item.setDltTopic(record.topic());
        item.setSourcePartition(record.partition()); item.setSourceOffset(record.offset());
        item.setPayload(record.value());
        item.setExceptionMessage(abbreviate(header(record, "kafka_dlt-exception-message")
                + (parseError == null ? "" : "; payloadParse=" + parseError)));
        item.setRecordStatus(parseError == null ? "RECORDED" : "RAW_ONLY"); item.setReplayCount(0);
        item.setCreateTime(now); item.setEditTime(now); item.setStatus(1);
        mapper.insert(item);
        meterRegistry.counter("stellaris_order_create_dlt_audit_total").increment();
    }

    public boolean replay(long orderNumber, long userId) {
        OrderCreateDltRecord record = mapper.selectOne(Wrappers.lambdaQuery(OrderCreateDltRecord.class)
                .eq(OrderCreateDltRecord::getOrderNumber, orderNumber)
                .eq(OrderCreateDltRecord::getUserId, userId));
        if (record == null) return false;
        if (!"RECORDED".equals(record.getRecordStatus()) && !"REPLAYED".equals(record.getRecordStatus())) {
            throw new IllegalStateException("Malformed RAW_ONLY DLT record cannot be replayed automatically");
        }
        String mainTopic = record.getDltTopic().endsWith(".DLT")
                ? record.getDltTopic().substring(0, record.getDltTopic().length() - 4) : record.getDltTopic();
        try {
            kafkaTemplate.send(mainTopic, String.valueOf(orderNumber), record.getPayload()).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("DLT replay publish failed", ex);
        }
        OrderCreateDltRecord update = new OrderCreateDltRecord();
        update.setRecordStatus("REPLAYED");
        update.setReplayCount(Objects.requireNonNullElse(record.getReplayCount(), 0) + 1);
        update.setEditTime(DateUtils.now());
        mapper.update(update, Wrappers.lambdaUpdate(OrderCreateDltRecord.class)
                .eq(OrderCreateDltRecord::getId, record.getId())
                .eq(OrderCreateDltRecord::getOrderNumber, orderNumber)
                .eq(OrderCreateDltRecord::getUserId, userId));
        meterRegistry.counter("stellaris_order_create_dlt_replay_total").increment();
        return true;
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) return "unknown consumer exception";
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private long fallbackRoute(ConsumerRecord<String, String> record) {
        long value = 1469598103934665603L;
        value = (value ^ record.topic().hashCode()) * 1099511628211L;
        value = (value ^ record.partition()) * 1099511628211L;
        value = (value ^ record.offset()) * 1099511628211L;
        value &= Long.MAX_VALUE;
        return value == 0L ? 1L : value;
    }

    private String abbreviate(String value) {
        if (value == null) return "unknown consumer exception";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
