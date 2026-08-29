package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.redis.RedisCache;
import com.stellaris.vo.SeatVo;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v5/reference 的最终锁座提交。候选座位由 Java/ZSET 在脚本外决定，脚本仅做 O(k) 校验和迁移。
 */
@Service
public class ReferenceSeatReservationService {
    private final RedisCache redisCache;
    private DefaultRedisScript<String> reserveManualScript;
    private DefaultRedisScript<String> releaseScript;
    private DefaultRedisScript<String> confirmSaleScript;

    public ReferenceSeatReservationService(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    @PostConstruct
    void init() {
        reserveManualScript = new DefaultRedisScript<>();
        reserveManualScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceReserveSeats.lua")));
        reserveManualScript.setResultType(String.class);
        releaseScript = new DefaultRedisScript<>();
        releaseScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceReleaseSeats.lua")));
        releaseScript.setResultType(String.class);
        confirmSaleScript = new DefaultRedisScript<>();
        confirmSaleScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceConfirmSale.lua")));
        confirmSaleScript.setResultType(String.class);
    }

    public SeatReservationResult reserveManual(SeatReservationRequest request) {
        validate(request);
        List<String> keys = keys(request);
        String raw = (String) redisCache.getInstance().execute(reserveManualScript, keys,
                request.intentId(), serializeSeats(request.seats()), request.eventPayload(),
                String.valueOf(request.userId()), String.valueOf(request.accountLimit()), request.requestFingerprint(),
                String.valueOf(request.expireAtMillis()), String.valueOf(request.receiptTtlMillis()));
        return parseReservationResult(raw);
    }

    /** 重试返回第一次锁座保存的原始消息，receipt 在终态清理 reservation 后仍保留一段时间。 */
    public String eventPayload(long programId, String intentId) {
        JSONObject receipt = receipt(programId, intentId);
        if (receipt != null) {
            return receipt.getString("eventPayload");
        }
        Object raw = redisCache.getInstance().opsForHash()
                .get(SeatReservationKeys.reservation(programId), intentId);
        if (raw == null) {
            return null;
        }
        return JSON.parseObject(String.valueOf(raw)).getString("eventPayload");
    }

    public String requestFingerprint(long programId, String intentId) {
        JSONObject receipt = receipt(programId, intentId);
        if (receipt != null) {
            return receipt.getString("requestFingerprint");
        }
        Object raw = redisCache.getInstance().opsForHash()
                .get(SeatReservationKeys.reservation(programId), intentId);
        return raw == null ? null : JSON.parseObject(String.valueOf(raw)).getString("requestFingerprint");
    }

    public String finalState(long programId, String intentId) {
        Object raw = redisCache.getInstance().opsForHash()
                .get(SeatReservationKeys.finalState(programId), intentId);
        return raw == null ? null : String.valueOf(raw);
    }

    private JSONObject receipt(long programId, String intentId) {
        Object raw = redisCache.getInstance().opsForValue().get(SeatReservationKeys.receipt(programId, intentId));
        return raw == null ? null : JSON.parseObject(String.valueOf(raw));
    }

    /** Fastjson 2.0.9 的兼容层不会序列化 record 组件，Lua 协议必须显式组装。 */
    static String serializeSeats(List<SeatReservationRequest.Seat> seats) {
        List<Map<String, Object>> payload = seats.stream().map(seat -> {
            Map<String, Object> item = new LinkedHashMap<>();
            // Redis Lua 的 cjson 只使用 IEEE-754 double。雪花 ID 若按 JSON number 传入会在
            // decode 后丢失精度，进而把一个实际存在于 ZSET 的座位误判为 SEAT_UNAVAILABLE。
            item.put("seatId", String.valueOf(seat.seatId()));
            item.put("ticketCategoryId", String.valueOf(seat.ticketCategoryId()));
            item.put("priceInCents", seat.priceInCents());
            item.put("ticketUserId", String.valueOf(seat.ticketUserId()));
            return item;
        }).toList();
        return JSON.toJSONString(payload);
    }

    static SeatReservationResult parseReservationResult(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("seat reservation script returned no result");
        }
        JSONObject response = JSON.parseObject(raw);
        boolean success = response.getBooleanValue("success");
        if (!success) {
            return new SeatReservationResult(false, response.getBooleanValue("replayed"),
                    response.getString("code"), List.of());
        }
        Object rawSeats = response.get("seats");
        List<SeatVo> seats = rawSeats == null ? List.of() : JSON.parseArray(JSON.toJSONString(rawSeats), SeatVo.class);
        return new SeatReservationResult(success, response.getBooleanValue("replayed"),
                response.getString("code"), seats == null ? List.of() : seats);
    }

    public void release(long programId, String intentId, List<Long> ticketCategoryIds) {
        if (intentId == null || intentId.isBlank() || ticketCategoryIds == null || ticketCategoryIds.isEmpty()) {
            throw new IllegalArgumentException("intentId and ticket categories are required for release");
        }
        List<String> keys = new ArrayList<>();
        keys.add(SeatReservationKeys.meta(programId));
        keys.add(SeatReservationKeys.owner(programId));
        keys.add(SeatReservationKeys.reservation(programId));
        keys.add(SeatReservationKeys.result(programId));
        keys.add(SeatReservationKeys.finalState(programId));
        keys.add(SeatReservationKeys.accountCount(programId));
        keys.add(SeatReservationKeys.expiration(programId));
        keys.add(SeatReservationKeys.eventStream(SeatReservationKeys.shard(programId)));
        ticketCategoryIds.stream().distinct().sorted()
                .forEach(categoryId -> keys.add(SeatReservationKeys.available(programId, categoryId)));
        String result = (String) redisCache.getInstance().execute(releaseScript, keys, intentId);
        if ("SOLD".equals(result)) {
            throw new IllegalStateException("sold reservation cannot be released: " + intentId);
        }
        if (!"1".equals(result)) {
            throw new IllegalStateException("reservation release failed without mutation, state=" + result);
        }
    }

    /** 把已锁座集合原子迁移到 sold；重复支付返回幂等成功。 */
    public void confirmSale(long programId, String intentId) {
        List<String> keys = List.of(SeatReservationKeys.owner(programId),
                SeatReservationKeys.reservation(programId), SeatReservationKeys.result(programId),
                SeatReservationKeys.sold(programId), SeatReservationKeys.finalState(programId),
                SeatReservationKeys.expiration(programId));
        String result = (String) redisCache.getInstance().execute(confirmSaleScript, keys, intentId);
        if (!"1".equals(result)) {
            throw new IllegalStateException("cannot confirm reservation as sold, state=" + result);
        }
    }

    public List<SeatVo> result(long programId, String intentId) {
        Object raw = redisCache.getInstance().opsForHash().get(SeatReservationKeys.result(programId), intentId);
        if (raw == null) {
            return List.of();
        }
        List<SeatVo> seats = JSON.parseArray(String.valueOf(raw), SeatVo.class);
        return seats == null ? List.of() : seats;
    }

    private List<String> keys(SeatReservationRequest request) {
        List<String> keys = new ArrayList<>();
        keys.add(SeatReservationKeys.ready(request.programId()));
        keys.add(SeatReservationKeys.maintenance(request.programId()));
        keys.add(SeatReservationKeys.meta(request.programId()));
        keys.add(SeatReservationKeys.owner(request.programId()));
        keys.add(SeatReservationKeys.reservation(request.programId()));
        keys.add(SeatReservationKeys.result(request.programId()));
        keys.add(SeatReservationKeys.accountCount(request.programId()));
        keys.add(SeatReservationKeys.eventStream(SeatReservationKeys.shard(request.programId())));
        keys.add(SeatReservationKeys.finalState(request.programId()));
        keys.add(SeatReservationKeys.expiration(request.programId()));
        keys.add(SeatReservationKeys.receipt(request.programId(), request.intentId()));
        request.seats().stream().map(SeatReservationRequest.Seat::ticketCategoryId).distinct().sorted()
                .forEach(categoryId -> keys.add(SeatReservationKeys.available(request.programId(), categoryId)));
        return keys;
    }

    static void validate(SeatReservationRequest request) {
        if (request.intentId() == null || request.intentId().isBlank() || request.programId() <= 0
                || request.userId() <= 0 || request.accountLimit() < 0
                || request.requestFingerprint() == null || request.requestFingerprint().isBlank()
                || request.eventPayload() == null || request.eventPayload().isBlank()
                || request.expireAtMillis() <= System.currentTimeMillis()
                || request.receiptTtlMillis() <= 0
                || request.seats() == null || request.seats().isEmpty() || request.seats().size() > 6) {
            throw new IllegalArgumentException("intentId, programId and seats are required");
        }
        Set<Long> seatIds = new LinkedHashSet<>();
        for (SeatReservationRequest.Seat seat : request.seats()) {
            if (seat.seatId() <= 0 || seat.ticketCategoryId() <= 0 || seat.priceInCents() < 0 || seat.ticketUserId() <= 0
                    || !seatIds.add(seat.seatId())) {
                throw new IllegalArgumentException("invalid or duplicate selected seat");
            }
        }
    }
}
