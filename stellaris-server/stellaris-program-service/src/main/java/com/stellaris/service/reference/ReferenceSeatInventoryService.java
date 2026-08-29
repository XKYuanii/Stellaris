package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.enums.SellStatus;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.redis.RedisCache;
import com.stellaris.vo.SeatVo;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * v5/reference 座位模型的显式预热和有限候选读取。
 * 预热只接受 MySQL 权威全量快照并保留 SOLD；下单路径发现 v5 Key 缺失必须 fail-closed。
 */
@Service
public class ReferenceSeatInventoryService {
    private static final int CANDIDATE_PAGE_SIZE = 64;
    private static final int TARGET_CANDIDATE_GROUPS = 32;

    private final RedisCache redisCache;
    private DefaultRedisScript<Long> beginPreheatScript;
    private DefaultRedisScript<Long> finishPreheatScript;

    public ReferenceSeatInventoryService(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    @PostConstruct
    void init() {
        beginPreheatScript = new DefaultRedisScript<>();
        beginPreheatScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceBeginPreheat.lua")));
        beginPreheatScript.setResultType(Long.class);
        finishPreheatScript = new DefaultRedisScript<>();
        finishPreheatScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceFinishPreheat.lua")));
        finishPreheatScript.setResultType(Long.class);
    }

    /** 原子关闭入口；检查与关闸之间不存在新锁座插入窗口。 */
    public void beginPreheat(long programId) {
        Number rawResult = (Number) redisCache.getInstance().execute(beginPreheatScript, List.of(
                SeatReservationKeys.ready(programId), SeatReservationKeys.maintenance(programId),
                SeatReservationKeys.owner(programId), SeatReservationKeys.reservation(programId)));
        long result = rawResult == null ? Long.MIN_VALUE : rawResult.longValue();
        if (result != 1L && result != 2L) {
            throw new StellarisFrameException(result == 0L
                    ? BaseCode.INVENTORY_PREHEAT_ACTIVE_RESERVATION : BaseCode.INVENTORY_PREHEAT_RUNNING);
        }
    }

    /**
     * 仅检查已经原子发布的完整快照。该检查用于冷启动兜底，正常下单热路径不会访问 MySQL。
     */
    public boolean isReady(long programId) {
        return Boolean.TRUE.equals(redisCache.getInstance().hasKey(SeatReservationKeys.ready(programId)));
    }

    @SuppressWarnings("unchecked")
    public void bootstrapFromSnapshot(long programId, List<SeatVo> allSeats) {
        validateSnapshot(allSeats);
        RedisTemplate<String, String> template = redisCache.getInstance();
        if (Boolean.TRUE.equals(template.hasKey(SeatReservationKeys.owner(programId)))
                || Boolean.TRUE.equals(template.hasKey(SeatReservationKeys.reservation(programId)))) {
            throw new IllegalStateException("active reservations prevent destructive bootstrap");
        }
        Set<Long> categories = new LinkedHashSet<>();
        Map<Object, Object> previousMeta = template.opsForHash().entries(SeatReservationKeys.meta(programId));
        for (Object value : previousMeta.values()) {
            SeatVo previous = JSON.parseObject(String.valueOf(value), SeatVo.class);
            if (previous.getTicketCategoryId() != null) categories.add(previous.getTicketCategoryId());
        }
        categories.addAll(allSeats.stream().map(SeatVo::getTicketCategoryId).filter(Objects::nonNull).toList());
        List<String> resetKeys = new ArrayList<>();
        resetKeys.add(SeatReservationKeys.ready(programId));
        resetKeys.add(SeatReservationKeys.meta(programId));
        resetKeys.add(SeatReservationKeys.owner(programId));
        resetKeys.add(SeatReservationKeys.reservation(programId));
        resetKeys.add(SeatReservationKeys.result(programId));
        resetKeys.add(SeatReservationKeys.sold(programId));
        resetKeys.add(SeatReservationKeys.finalState(programId));
        resetKeys.add(SeatReservationKeys.expiration(programId));
        resetKeys.add(SeatReservationKeys.version(programId));
        categories.forEach(categoryId -> resetKeys.add(SeatReservationKeys.available(programId, categoryId)));
        template.delete(resetKeys);
        Map<String, String> meta = allSeats.stream().collect(Collectors.toMap(
                seat -> String.valueOf(seat.getId()), ReferenceSeatInventoryService::metadataJson));
        template.opsForHash().putAll(SeatReservationKeys.meta(programId), meta);

        Map<Long, List<SeatVo>> availableByCategory = allSeats.stream()
                .filter(seat -> seat.getSellStatus() == SellStatus.NO_SOLD.getCode())
                .collect(Collectors.groupingBy(SeatVo::getTicketCategoryId));
        for (Map.Entry<Long, List<SeatVo>> entry : availableByCategory.entrySet()) {
            String availableKey = SeatReservationKeys.available(programId, entry.getKey());
            for (SeatVo seat : entry.getValue()) {
                template.opsForZSet().add(availableKey, String.valueOf(seat.getId()), score(seat));
            }
        }
        allSeats.stream().filter(seat -> seat.getSellStatus() == SellStatus.SOLD.getCode())
                .forEach(seat -> template.opsForSet().add(SeatReservationKeys.sold(programId), String.valueOf(seat.getId())));
        Number finished = (Number) template.execute(finishPreheatScript, List.of(
                SeatReservationKeys.ready(programId), SeatReservationKeys.maintenance(programId),
                SeatReservationKeys.version(programId)), String.valueOf(allSeats.size()), UUID.randomUUID().toString());
        if (finished == null || finished.longValue() != 1L) {
            throw new IllegalStateException("inventory preheat ownership was lost before publish");
        }
    }

    /** 所有校验必须在删除旧快照前完成，坏数据不能把一个原本可用的库存变成半成品。 */
    static void validateSnapshot(List<SeatVo> allSeats) {
        if (allSeats == null || allSeats.isEmpty()) {
            throw new IllegalArgumentException("full seat snapshot is required for bootstrap");
        }
        Set<Long> seatIds = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        for (SeatVo seat : allSeats) {
            if (seat == null || seat.getId() == null || seat.getTicketCategoryId() == null
                    || seat.getRowCode() == null || seat.getColCode() == null || seat.getPrice() == null
                    || seat.getSellStatus() == null) {
                throw new IllegalArgumentException("seat snapshot contains an incomplete seat");
            }
            if (!Objects.equals(seat.getSellStatus(), SellStatus.NO_SOLD.getCode())
                    && !Objects.equals(seat.getSellStatus(), SellStatus.SOLD.getCode())) {
                throw new IllegalStateException("bootstrap requires only NO_SOLD or SOLD seats");
            }
            if (!seatIds.add(seat.getId())) {
                throw new IllegalStateException("duplicate seat id " + seat.getId());
            }
            String coordinate = seat.getTicketCategoryId() + ":" + seat.getRowCode() + ":" + seat.getColCode();
            if (!coordinates.add(coordinate)) {
                throw new IllegalStateException("duplicate seat coordinate " + coordinate);
            }
        }
    }

    /** 构建失败只解除维护占用，不恢复 ready；库存保持 fail-closed，下一次完整预热可接管。 */
    public void abortPreheat(long programId) {
        RedisTemplate<String, String> template = redisCache.getInstance();
        if (!Boolean.TRUE.equals(template.hasKey(SeatReservationKeys.ready(programId)))) {
            template.delete(SeatReservationKeys.maintenance(programId));
        }
    }

    public List<SeatVo> findCandidates(long programId, long ticketCategoryId, int requestedCount,
                                       int candidateLimit) {
        List<List<SeatVo>> groups = findCandidateGroups(programId, ticketCategoryId, requestedCount,
                candidateLimit, "legacy-candidate");
        return groups.isEmpty() ? List.of() : groups.get(0);
    }

    /**
     * 从 requestId 的稳定 Hash 对应的 ZSET rank 开始读取一个有界窗口，构造多组连续座位。
     * 不同请求会自然分散到票档的不同位置；窗口内冲突时，编排层可以换下一组而不扩大 Lua 职责。
     */
    @SuppressWarnings("unchecked")
    public List<List<SeatVo>> findCandidateGroups(long programId, long ticketCategoryId, int requestedCount,
                                                   int candidateLimit, String requestId) {
        if (requestedCount <= 0 || candidateLimit < requestedCount) {
            throw new IllegalArgumentException("candidateLimit must cover requestedCount");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required for deterministic candidate selection");
        }
        RedisTemplate<String, String> template = redisCache.getInstance();
        String availableKey = SeatReservationKeys.available(programId, ticketCategoryId);
        String metaKey = SeatReservationKeys.meta(programId);
        if (!Boolean.TRUE.equals(template.hasKey(SeatReservationKeys.ready(programId)))
                || !Boolean.TRUE.equals(template.hasKey(availableKey)) || !Boolean.TRUE.equals(template.hasKey(metaKey))) {
            throw new IllegalStateException("v5 seat inventory is not initialized; fail closed");
        }
        Long rawAvailableCount = template.opsForZSet().size(availableKey);
        long availableCount = rawAvailableCount == null ? 0 : rawAvailableCount;
        if (availableCount < requestedCount) {
            return List.of();
        }
        int inspectLimit = (int) Math.min(availableCount, candidateLimit);
        long startRank = Math.floorMod((long) requestId.hashCode(), availableCount);
        List<SeatVo> candidates = new ArrayList<>(inspectLimit);
        int inspected = 0;
        while (inspected < inspectLimit) {
            int pageSize = Math.min(CANDIDATE_PAGE_SIZE, inspectLimit - inspected);
            List<String> ids = rangeCyclic(template, availableKey, availableCount,
                    startRank, inspected, pageSize);
            if (ids.isEmpty()) {
                break;
            }
            List<Object> rawSeats = template.opsForHash().multiGet(metaKey, new ArrayList<Object>(ids));
            if (rawSeats == null || rawSeats.size() != ids.size() || rawSeats.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("v5 seat metadata is incomplete for available candidates");
            }
            rawSeats.stream().map(value -> JSON.parseObject(String.valueOf(value), SeatVo.class))
                    .forEach(candidates::add);
            inspected += ids.size();
            List<List<SeatVo>> groups = buildCandidateGroups(candidates, requestedCount);
            if (groups.size() >= TARGET_CANDIDATE_GROUPS) {
                return groups;
            }
            if (ids.size() < pageSize) {
                break;
            }
        }
        return buildCandidateGroups(candidates, requestedCount);
    }

    private List<String> rangeCyclic(RedisTemplate<String, String> template, String availableKey,
                                     long availableCount, long startRank, int offset, int size) {
        long rank = (startRank + offset) % availableCount;
        int firstSize = (int) Math.min(size, availableCount - rank);
        List<String> ids = new ArrayList<>(size);
        Set<String> first = template.opsForZSet().range(availableKey, rank, rank + firstSize - 1);
        if (first != null) {
            ids.addAll(first);
        }
        int remaining = size - ids.size();
        if (remaining > 0) {
            Set<String> wrapped = template.opsForZSet().range(availableKey, 0, remaining - 1L);
            if (wrapped != null) {
                ids.addAll(wrapped);
            }
        }
        return ids;
    }

    static List<List<SeatVo>> buildCandidateGroups(List<SeatVo> candidates, int requestedCount) {
        if (candidates == null || candidates.size() < requestedCount || requestedCount <= 0) {
            return List.of();
        }
        List<List<SeatVo>> groups = new ArrayList<>();
        for (int start = 0; start <= candidates.size() - requestedCount; start++) {
            boolean adjacent = true;
            for (int index = start; index < start + requestedCount - 1; index++) {
                SeatVo current = candidates.get(index);
                SeatVo next = candidates.get(index + 1);
                if (!current.getRowCode().equals(next.getRowCode())
                        || next.getColCode() - current.getColCode() != 1) {
                    adjacent = false;
                    break;
                }
            }
            if (adjacent) {
                groups.add(List.copyOf(candidates.subList(start, start + requestedCount)));
            }
        }
        return List.copyOf(groups);
    }

    /** 按请求顺序读取服务端权威座位快照；缺一个都拒绝，绝不采用客户端价格和票档。 */
    public List<SeatVo> findByIds(long programId, List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty() || seatIds.stream().distinct().count() != seatIds.size()) {
            throw new IllegalArgumentException("seat ids are required and must be unique");
        }
        RedisTemplate<String, String> template = redisCache.getInstance();
        if (!Boolean.TRUE.equals(template.hasKey(SeatReservationKeys.ready(programId)))) {
            throw new IllegalStateException("v5 seat inventory is not initialized; fail closed");
        }
        List<String> fields = seatIds.stream().map(String::valueOf).toList();
        List<Object> raw = template.opsForHash().multiGet(SeatReservationKeys.meta(programId),
                new ArrayList<Object>(fields));
        if (raw.size() != fields.size() || raw.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("seat metadata is incomplete");
        }
        return raw.stream().map(value -> JSON.parseObject(String.valueOf(value), SeatVo.class)).toList();
    }

    static String metadataJson(SeatVo seat) {
        JSONObject json = (JSONObject) JSON.toJSON(seat);
        // 这些字段会被 Redis Lua 的 cjson decode/encode。必须使用字符串承载 64 位 ID，
        // 否则超过 2^53 的雪花 ID 会被舍入，锁座结果和后续订单事件都会拿到错误座位号。
        json.put("id", String.valueOf(seat.getId()));
        if (seat.getProgramId() != null) {
            json.put("programId", String.valueOf(seat.getProgramId()));
        }
        json.put("ticketCategoryId", String.valueOf(seat.getTicketCategoryId()));
        json.put("priceInCents", seat.getPrice().movePointRight(2).longValueExact());
        return json.toJSONString();
    }

    static double score(SeatVo seat) {
        return seat.getRowCode() * 1_000_000D + seat.getColCode();
    }

}
