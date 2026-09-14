package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.domain.OrderReservationRedisKeys;
import com.stellaris.domain.OrderReservationStreamKeys;
import com.stellaris.enums.SellStatus;
import com.stellaris.redis.RedisCache;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies terminal reservation transitions directly to the shared sale-shard Redis keys. Program
 * admits reservations; Order owns the durable terminal fact and therefore performs confirmation
 * and release without a reverse service call.
 */
@Service
public class OrderReservationReleaseService {
    private final RedisCache redisCache;
    private DefaultRedisScript<String> releaseScript;
    private DefaultRedisScript<String> confirmSaleScript;

    public OrderReservationReleaseService(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    @PostConstruct
    void init() {
        releaseScript = script("lua/orderReservationRelease.lua");
        confirmSaleScript = script("lua/orderReservationConfirmSale.lua");
    }

    public void release(long programId, String intentId) {
        transition(programId, intentId, SellStatus.NO_SOLD.getCode());
    }

    public void confirmSale(long programId, String intentId) {
        transition(programId, intentId, SellStatus.SOLD.getCode());
    }

    public void transition(long programId, String intentId, int targetStatus) {
        if (programId <= 0 || intentId == null || intentId.isBlank()) {
            throw new IllegalArgumentException("programId and intentId are required for reservation transition");
        }
        if (Objects.equals(targetStatus, SellStatus.NO_SOLD.getCode())) {
            releaseDirect(programId, intentId);
            return;
        }
        if (Objects.equals(targetStatus, SellStatus.SOLD.getCode())) {
            confirmDirect(programId, intentId);
            return;
        }
        throw new IllegalArgumentException("unsupported reservation target status: " + targetStatus);
    }

    private void releaseDirect(long programId, String intentId) {
        RedisTemplate<String, String> template = redisCache.getInstance();
        List<String> keys = new ArrayList<>(List.of(
                OrderReservationRedisKeys.meta(programId),
                OrderReservationRedisKeys.owner(programId),
                OrderReservationRedisKeys.reservation(programId),
                OrderReservationRedisKeys.result(programId),
                OrderReservationRedisKeys.finalState(programId),
                OrderReservationRedisKeys.accountCount(programId),
                OrderReservationRedisKeys.expiration(programId),
                OrderReservationRedisKeys.eventStream(programId),
                OrderReservationStreamKeys.expirationIndex(OrderReservationStreamKeys.shard(programId))));
        resolveCategoryIds(template, programId, intentId).forEach(categoryId ->
                keys.add(OrderReservationRedisKeys.available(programId, categoryId)));
        String result = template.execute(releaseScript, keys, intentId,
                OrderReservationStreamKeys.expirationMember(programId, intentId));
        if (!"1".equals(result)) {
            throw conflict(result, intentId);
        }
    }

    private void confirmDirect(long programId, String intentId) {
        RedisTemplate<String, String> template = redisCache.getInstance();
        List<String> keys = List.of(
                OrderReservationRedisKeys.owner(programId),
                OrderReservationRedisKeys.reservation(programId),
                OrderReservationRedisKeys.result(programId),
                OrderReservationRedisKeys.sold(programId),
                OrderReservationRedisKeys.finalState(programId),
                OrderReservationRedisKeys.expiration(programId),
                OrderReservationStreamKeys.expirationIndex(OrderReservationStreamKeys.shard(programId)));
        String result = template.execute(confirmSaleScript, keys, intentId,
                OrderReservationStreamKeys.expirationMember(programId, intentId));
        if (!"1".equals(result)) {
            throw conflict(result, intentId);
        }
    }

    private List<Long> resolveCategoryIds(RedisTemplate<String, String> template,
                                          long programId, String intentId) {
        Object raw = template.opsForHash().get(OrderReservationRedisKeys.reservation(programId), intentId);
        if (raw == null) return List.of();
        JSONObject reservation;
        try {
            reservation = JSON.parseObject(String.valueOf(raw));
        } catch (RuntimeException malformed) {
            throw conflict("MALFORMED_RESERVATION", intentId);
        }
        if (reservation == null || reservation.getJSONArray("seats") == null
                || reservation.getJSONArray("seats").isEmpty()) {
            throw conflict("MISSING_SEAT_METADATA", intentId);
        }
        List<Long> categoryIds = reservation.getJSONArray("seats").stream()
                .map(item -> JSON.parseObject(JSON.toJSONString(item)).getLong("ticketCategoryId"))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (categoryIds.isEmpty()) {
            throw conflict("MISSING_CATEGORY_METADATA", intentId);
        }
        return categoryIds;
    }

    private ReservationTransitionConflictException conflict(String code, String intentId) {
        return new ReservationTransitionConflictException(
                code == null || code.isBlank() ? "EMPTY_RESULT" : code, intentId);
    }

    private static DefaultRedisScript<String> script(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(String.class);
        return script;
    }
}
