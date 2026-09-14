package com.stellaris.service.stream;

import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.entity.OrderStreamFailure;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderStreamFailureMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.service.reference.OrderReservationReleaseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStreamFailureServiceTest {

    @Test
    void replayIsRejectedAfterTheOriginalReservationWasReleased() {
        OrderStreamFailureMapper mapper = mock(OrderStreamFailureMapper.class);
        RedisCache redisCache = mock(RedisCache.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        OrderStreamFailure failure = failure("RECORDED");
        when(mapper.selectById(7L)).thenReturn(failure);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn("FINAL_RELEASED");
        OrderStreamFailureService service = new OrderStreamFailureService(mapper, mock(OrderMapper.class),
                mock(UidGenerator.class),
                redisCache, mock(OrderReservationReleaseService.class), new SimpleMeterRegistry());

        assertThat(service.replay(7L)).isFalse();

        verify(mapper, never()).update(any(), any());
    }

    @Test
    void replayAtomicallyChecksTheActiveReservationBeforeAppendingTheEnvelope() {
        OrderStreamFailureMapper mapper = mock(OrderStreamFailureMapper.class);
        RedisCache redisCache = mock(RedisCache.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisCache.getInstance()).thenReturn(redisTemplate);
        when(mapper.update(any(), any())).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(failure("RECORDED"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn("OK:1710000000000-0");
        OrderStreamFailureService service = new OrderStreamFailureService(mapper, mock(OrderMapper.class),
                mock(UidGenerator.class), redisCache, mock(OrderReservationReleaseService.class),
                new SimpleMeterRegistry());

        assertThat(service.replay(7L)).isTrue();

        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                eq("intent-malformed"), eq("205"), eq("{malformed-json"), any());
        ArgumentCaptor<OrderStreamFailure> update = ArgumentCaptor.forClass(OrderStreamFailure.class);
        verify(mapper).update(update.capture(), any());
        assertThat(update.getValue().getRecordStatus()).isEqualTo("REPLAYED");
    }

    @Test
    void manualReleaseUsesOwnerValidatingLuaBeforeResolvingAudit() {
        OrderStreamFailureMapper mapper = mock(OrderStreamFailureMapper.class);
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getInstance()).thenReturn(mock(StringRedisTemplate.class));
        OrderReservationReleaseService releaseService = mock(OrderReservationReleaseService.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        when(mapper.selectById(7L)).thenReturn(failure("MANUAL_REQUIRED"));
        when(mapper.update(any(), any())).thenReturn(1);
        OrderStreamFailureService service = new OrderStreamFailureService(mapper, orderMapper,
                mock(UidGenerator.class),
                redisCache, releaseService, new SimpleMeterRegistry());

        assertThat(service.releaseReservation(7L)).isTrue();

        verify(releaseService).release(205L, "intent-malformed");
        ArgumentCaptor<OrderStreamFailure> update = ArgumentCaptor.forClass(OrderStreamFailure.class);
        verify(mapper).update(update.capture(), any());
        assertThat(update.getValue().getRecordStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void manualReleaseIsRejectedWhenTheIntentHasAnActiveTradeOrder() {
        OrderStreamFailureMapper mapper = mock(OrderStreamFailureMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getInstance()).thenReturn(mock(StringRedisTemplate.class));
        when(mapper.selectById(7L)).thenReturn(failure("MANUAL_REQUIRED"));
        when(orderMapper.selectCount(any())).thenReturn(1L);
        OrderReservationReleaseService releaseService = mock(OrderReservationReleaseService.class);
        OrderStreamFailureService service = new OrderStreamFailureService(mapper, orderMapper,
                mock(UidGenerator.class), redisCache, releaseService, new SimpleMeterRegistry());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.releaseReservation(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active trade order");

        verify(releaseService, never()).release(anyLong(), any());
        verify(mapper, never()).update(any(), any());
    }

    private OrderStreamFailure failure(String status) {
        OrderStreamFailure failure = new OrderStreamFailure();
        failure.setId(7L);
        failure.setStreamKey("stellaris:{sale:13}:reservation:event:stream");
        failure.setPayload("{malformed-json");
        failure.setProgramId(205L);
        failure.setIntentId("intent-malformed");
        failure.setRecordStatus(status);
        failure.setReplayCount(0);
        return failure;
    }
}
