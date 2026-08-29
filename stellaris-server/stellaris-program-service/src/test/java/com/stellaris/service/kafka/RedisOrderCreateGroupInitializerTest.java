package com.stellaris.service.kafka;

import com.stellaris.redis.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOrderCreateGroupInitializerTest {
    private StreamOperations<String, Object, Object> streamOperations;
    private RedisOrderCreateGroupInitializer initializer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisCache redisCache = mock(RedisCache.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        RedisOrderRelayProperties properties = new RedisOrderRelayProperties();
        properties.setGroup("stellaris-order-relay");
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        initializer = new RedisOrderCreateGroupInitializer(redisCache, properties);
    }

    @Test
    void createsGroupThroughSupportedStreamOperationsOnlyOnce() {
        initializer.ensureGroup(4);
        initializer.ensureGroup(4);

        verify(streamOperations, times(1)).createGroup(
                "stellaris:{sale:4}:reservation:event:stream", ReadOffset.from("0-0"), "stellaris-order-relay");
    }

    @Test
    void existingGroupIsTreatedAsInitialized() {
        when(streamOperations.createGroup(
                "stellaris:{sale:4}:reservation:event:stream", ReadOffset.from("0-0"), "stellaris-order-relay"))
                .thenThrow(new InvalidDataAccessApiUsageException("BUSYGROUP Consumer Group name already exists"));

        initializer.ensureGroup(4);
        initializer.ensureGroup(4);

        verify(streamOperations, times(1)).createGroup(
                "stellaris:{sale:4}:reservation:event:stream", ReadOffset.from("0-0"), "stellaris-order-relay");
    }
}
