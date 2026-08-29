package com.stellaris.service.kafka;

import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.SeatReservationKeys;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.List;

/** Redis Stream 创建订单死信的原子转移与显式重放。 */
@Service
public class RedisOrderCreateDeadLetterService {
    private final StringRedisTemplate redisTemplate;
    private final RedisOrderRelayProperties properties;
    private final MeterRegistry meterRegistry;
    private DefaultRedisScript<Long> deadLetterScript;
    private DefaultRedisScript<Long> replayDeadScript;

    public RedisOrderCreateDeadLetterService(RedisCache redisCache, RedisOrderRelayProperties properties,
                                             MeterRegistry meterRegistry) {
        this.redisTemplate = (StringRedisTemplate) redisCache.getInstance();
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        deadLetterScript = script("lua/referenceDeadLetterStream.lua");
        replayDeadScript = script("lua/referenceReplayDeadStream.lua");
    }

    public void moveToDead(int shard, String stream, MapRecord<String, Object, Object> record, String reason) {
        Long result = redisTemplate.execute(deadLetterScript,
                List.of(stream, SeatReservationKeys.eventDeadStream(shard)), properties.getGroup(),
                record.getId().getValue(), field(record, "intentId"), field(record, "payload"), reason);
        if (result == null || result != 1L) {
            throw new IllegalStateException("cannot move Redis Stream record to dead letter: " + record.getId());
        }
        meterRegistry.counter("stellaris_order_stream_relay_total", "result", "dead").increment();
    }

    public boolean replayDead(int shard, String recordId) {
        if (shard < 0 || shard >= SeatReservationKeys.SALE_SHARD_COUNT
                || recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("valid shard and recordId are required");
        }
        String dead = SeatReservationKeys.eventDeadStream(shard);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(dead, Range.closed(recordId, recordId));
        if (records == null || records.isEmpty()) return false;
        MapRecord<String, Object, Object> record = records.get(0);
        Long result = redisTemplate.execute(replayDeadScript,
                List.of(dead, SeatReservationKeys.eventStream(shard)), recordId,
                field(record, "intentId"), field(record, "payload"));
        return result != null && result == 1L;
    }

    private DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

    static String field(MapRecord<String, Object, Object> record, String name) {
        Object value = record.getValue().get(name);
        if (value == null) throw new IllegalArgumentException("stream record missing field " + name);
        return String.valueOf(value);
    }
}
