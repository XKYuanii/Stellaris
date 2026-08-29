package com.stellaris.service;

import com.stellaris.domain.ReconciliationTaskData;
import com.stellaris.core.SpringUtil;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderTaskServiceTest {

    @BeforeAll
    static void initializeSpringUtil() {
        new SpringUtil().initialize(new GenericApplicationContext());
    }

    @Test
    void onlySelectsRedisRecordsBelongingToCurrentDatabaseOrders() {
        OrderTaskService service = new OrderTaskService();
        Map<String, String> allRecords = new LinkedHashMap<>();
        allRecords.put("reduce_10_1", "first");
        allRecords.put("reduce_20_2", "second");

        Map<String, String> filtered = ReflectionTestUtils.invokeMethod(
                service, "filterRedisRecords", allRecords, Set.of(10L));

        assertEquals(Map.of("reduce_10_1", "first"), filtered);
    }

    @Test
    void allowsProgramTaskToFinishWhenOrderSideWasAlreadyReconciled() {
        RedisCache redisCache = mock(RedisCache.class);
        OrderProgramMapper orderProgramMapper = mock(OrderProgramMapper.class);
        when(redisCache.getAllMapForHash(any(RedisKeyBuild.class), eq(String.class)))
                .thenReturn(Collections.emptyMap());
        when(orderProgramMapper.selectList(any())).thenReturn(Collections.emptyList());
        OrderTaskService service = new OrderTaskService();
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "orderProgramMapper", orderProgramMapper);

        ReconciliationTaskData result = service.reconciliationTask(100L);

        assertNotNull(result);
        assertEquals(100L, result.getProgramId());
    }
}
