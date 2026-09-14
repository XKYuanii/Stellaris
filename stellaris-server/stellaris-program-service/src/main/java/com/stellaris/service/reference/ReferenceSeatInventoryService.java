package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.client.OrderClient;
import com.stellaris.common.ApiResponse;
import com.stellaris.dto.SeatInventoryInitializeDto;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.dto.SeatInventoryQueryDto;
import com.stellaris.enums.SellStatus;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.redis.RedisCache;
import com.stellaris.vo.SeatVo;
import com.stellaris.domain.OrderReservationStreamKeys;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Date;
import java.util.LinkedHashMap;

/**
 * v5/reference 座位模型的显式预热和有限候选读取。
 * 预热只接受 MySQL 权威全量快照并保留 SOLD；下单路径发现 v5 Key 缺失必须 fail-closed。
 */
@Service
public class ReferenceSeatInventoryService {
    private static final int CANDIDATE_PAGE_SIZE = 16;
    private static final int TARGET_CANDIDATE_GROUPS = 5;

    private final RedisCache redisCache;
    private final OrderClient orderClient;
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;
    private DefaultRedisScript<Long> beginPreheatScript;
    private DefaultRedisScript<Long> finishPreheatScript;
    private DefaultRedisScript<String> findCandidatesScript;

    public ReferenceSeatInventoryService(RedisCache redisCache, OrderClient orderClient,
                                         ReactiveStringRedisTemplate reactiveRedisTemplate) {
        this.redisCache = redisCache;
        this.orderClient = orderClient;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
    }

    @PostConstruct
    void init() {
        beginPreheatScript = new DefaultRedisScript<>();
        beginPreheatScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceBeginPreheat.lua")));
        beginPreheatScript.setResultType(Long.class);
        finishPreheatScript = new DefaultRedisScript<>();
        finishPreheatScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/referenceFinishPreheat.lua")));
        finishPreheatScript.setResultType(Long.class);
        findCandidatesScript = new DefaultRedisScript<>();
        findCandidatesScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/referenceFindSeatCandidates.lua")));
        findCandidatesScript.setResultType(String.class);
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
        String saleVersion = saleVersion(programId, allSeats);
        publishTradeInventory(programId, saleVersion, allSeats);
        TradeStateSnapshot tradeState = applyTradeState(programId, allSeats);
        List<SeatVo> authoritativeSeats = tradeState.seats();
        validateSnapshot(authoritativeSeats);
        validateTradeState(tradeState.stateBySeat());
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
        categories.addAll(authoritativeSeats.stream().map(SeatVo::getTicketCategoryId)
                .filter(Objects::nonNull).toList());
        List<String> resetKeys = new ArrayList<>();
        resetKeys.add(SeatReservationKeys.ready(programId));
        resetKeys.add(SeatReservationKeys.meta(programId));
        resetKeys.add(SeatReservationKeys.owner(programId));
        resetKeys.add(SeatReservationKeys.reservation(programId));
        resetKeys.add(SeatReservationKeys.result(programId));
        resetKeys.add(SeatReservationKeys.sold(programId));
        resetKeys.add(SeatReservationKeys.finalState(programId));
        resetKeys.add(SeatReservationKeys.expiration(programId));
        resetKeys.add(SeatReservationKeys.accountCount(programId));
        resetKeys.add(SeatReservationKeys.version(programId));
        categories.forEach(categoryId -> resetKeys.add(SeatReservationKeys.available(programId, categoryId)));
        template.delete(resetKeys);
        Map<String, String> meta = authoritativeSeats.stream().collect(Collectors.toMap(
                seat -> String.valueOf(seat.getId()), ReferenceSeatInventoryService::metadataJson));
        template.opsForHash().putAll(SeatReservationKeys.meta(programId), meta);

        Map<Long, List<SeatVo>> availableByCategory = authoritativeSeats.stream()
                .filter(seat -> seat.getSellStatus() == SellStatus.NO_SOLD.getCode())
                .collect(Collectors.groupingBy(SeatVo::getTicketCategoryId));
        for (Map.Entry<Long, List<SeatVo>> entry : availableByCategory.entrySet()) {
            String availableKey = SeatReservationKeys.available(programId, entry.getKey());
            for (SeatVo seat : entry.getValue()) {
                template.opsForZSet().add(availableKey, String.valueOf(seat.getId()), score(seat));
            }
        }
        authoritativeSeats.stream().filter(seat -> seat.getSellStatus() == SellStatus.SOLD.getCode())
                .forEach(seat -> template.opsForSet().add(SeatReservationKeys.sold(programId), String.valueOf(seat.getId())));
        rebuildLockedAndSoldState(template, programId, authoritativeSeats, tradeState.stateBySeat());
        Number finished = (Number) template.execute(finishPreheatScript, List.of(
                SeatReservationKeys.ready(programId), SeatReservationKeys.maintenance(programId),
                SeatReservationKeys.version(programId)), String.valueOf(authoritativeSeats.size()), saleVersion);
        if (finished == null || finished.longValue() != 1L) {
            throw new IllegalStateException("inventory preheat ownership was lost before publish");
        }
    }

    /** 当前已发布销售版本会进入 Stream 消息，由交易库拒绝旧版本写入。 */
    public String currentVersion(long programId) {
        Object version = redisCache.getInstance().opsForValue().get(SeatReservationKeys.version(programId));
        return version == null ? null : String.valueOf(version);
    }

    private void publishTradeInventory(long programId, String saleVersion, List<SeatVo> seats) {
        SeatInventoryInitializeDto dto = new SeatInventoryInitializeDto();
        dto.setProgramId(programId);
        dto.setSaleVersion(saleVersion);
        dto.setSeats(seats.stream().map(seat -> {
            SeatInventorySnapshotDto snapshot = new SeatInventorySnapshotDto();
            snapshot.setSeatId(seat.getId());
            snapshot.setTicketCategoryId(seat.getTicketCategoryId());
            snapshot.setPriceInCents(seat.getPrice().movePointRight(2).longValueExact());
            snapshot.setSellStatus(seat.getSellStatus());
            return snapshot;
        }).toList());
        ApiResponse<Boolean> response = orderClient.initializeSeatInventory(dto);
        if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())
                || !Boolean.TRUE.equals(response.getData())) {
            throw new IllegalStateException("trade inventory initialization failed");
        }
    }

    private TradeStateSnapshot applyTradeState(long programId, List<SeatVo> layoutSeats) {
        SeatInventoryQueryDto query = new SeatInventoryQueryDto();
        query.setProgramId(programId);
        ApiResponse<List<SeatInventorySnapshotDto>> response = orderClient.currentSeatInventory(query);
        if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())
                || response.getData() == null) {
            throw new IllegalStateException("cannot read authoritative trade inventory");
        }
        Map<Long, SeatInventorySnapshotDto> stateBySeat = response.getData().stream()
                .collect(Collectors.toMap(SeatInventorySnapshotDto::getSeatId, item -> item));
        if (stateBySeat.size() != layoutSeats.size()) {
            throw new IllegalStateException("trade inventory is incomplete for Redis rebuild");
        }
        for (SeatVo seat : layoutSeats) {
            SeatInventorySnapshotDto state = stateBySeat.get(seat.getId());
            if (state == null) throw new IllegalStateException("trade inventory misses seat " + seat.getId());
            seat.setTicketCategoryId(state.getTicketCategoryId());
            seat.setPrice(java.math.BigDecimal.valueOf(state.getPriceInCents(), 2));
            seat.setSellStatus(state.getSellStatus());
        }
        return new TradeStateSnapshot(layoutSeats, stateBySeat);
    }

    private void rebuildLockedAndSoldState(RedisTemplate<String, String> template, long programId,
                                           List<SeatVo> seats,
                                           Map<Long, SeatInventorySnapshotDto> stateBySeat) {
        clearProgramExpirationIndex(template, programId);
        Map<Long, SeatVo> seatById = seats.stream().collect(Collectors.toMap(SeatVo::getId, seat -> seat));
        Map<String, List<SeatInventorySnapshotDto>> lockedByReservation = lockedByReservation(stateBySeat);
        Map<Long, Long> accountCounts = new LinkedHashMap<>();
        stateBySeat.values().stream()
                .filter(state -> Objects.equals(state.getSellStatus(), SellStatus.LOCK.getCode())
                        || Objects.equals(state.getSellStatus(), SellStatus.SOLD.getCode()))
                .forEach(state -> {
                    requireOwnedState(state);
                    accountCounts.merge(state.getUserId(), 1L, Long::sum);
                    if (Objects.equals(state.getSellStatus(), SellStatus.SOLD.getCode())) {
                        template.opsForHash().put(SeatReservationKeys.finalState(programId),
                                state.getReservationId(), "SOLD");
                    }
                });
        if (!accountCounts.isEmpty()) {
            Map<String, String> values = accountCounts.entrySet().stream().collect(Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()), entry -> String.valueOf(entry.getValue())));
            template.opsForHash().putAll(SeatReservationKeys.accountCount(programId), values);
        }
        for (Map.Entry<String, List<SeatInventorySnapshotDto>> entry : lockedByReservation.entrySet()) {
            String intentId = entry.getKey();
            List<SeatInventorySnapshotDto> locked = entry.getValue();
            SeatInventorySnapshotDto first = locked.get(0);
            requireLockedGroup(intentId, locked);
            List<Map<String, Object>> reservedSeats = locked.stream().map(state -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("seatId", String.valueOf(state.getSeatId()));
                item.put("ticketCategoryId", String.valueOf(state.getTicketCategoryId()));
                item.put("priceInCents", state.getPriceInCents());
                item.put("ticketUserId", String.valueOf(state.getTicketUserId()));
                return item;
            }).toList();
            List<JSONObject> results = locked.stream().map(state -> {
                JSONObject result = JSON.parseObject(metadataJson(seatById.get(state.getSeatId())));
                result.put("ticketUserId", String.valueOf(state.getTicketUserId()));
                return result;
            }).toList();
            String eventPayload = JSON.toJSONString(Map.of(
                    "orderNumber", String.valueOf(first.getOrderNumber()),
                    "programId", String.valueOf(programId),
                    "userId", String.valueOf(first.getUserId()),
                    "intentId", intentId));
            Map<String, Object> reservation = new LinkedHashMap<>();
            reservation.put("userId", String.valueOf(first.getUserId()));
            reservation.put("ticketCount", locked.size());
            reservation.put("seats", reservedSeats);
            reservation.put("requestFingerprint", first.getRequestFingerprint());
            reservation.put("eventPayload", eventPayload);
            template.opsForHash().put(SeatReservationKeys.reservation(programId), intentId,
                    JSON.toJSONString(reservation));
            template.opsForHash().put(SeatReservationKeys.result(programId), intentId,
                    JSON.toJSONString(results));
            locked.forEach(state -> template.opsForHash().put(SeatReservationKeys.owner(programId),
                    String.valueOf(state.getSeatId()), intentId));
            // 索引保存业务到期时刻。扫描任务自己减去 grace，不能在写入时再加一次宽限期。
            long expireAt = expirationScore(first);
            template.opsForZSet().add(SeatReservationKeys.expiration(programId), intentId, expireAt);
            template.opsForZSet().add(OrderReservationStreamKeys.expirationIndex(SeatReservationKeys.shard(programId)),
                    OrderReservationStreamKeys.expirationMember(programId, intentId), expireAt);
        }
    }

    private void clearProgramExpirationIndex(RedisTemplate<String, String> template, long programId) {
        String index = OrderReservationStreamKeys.expirationIndex(SeatReservationKeys.shard(programId));
        List<String> stale = new ArrayList<>();
        try (Cursor<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> cursor =
                     template.opsForZSet().scan(index, ScanOptions.scanOptions()
                             .match(programId + "|*").count(1000).build())) {
            cursor.forEachRemaining(tuple -> {
                if (tuple.getValue() != null) stale.add(tuple.getValue());
            });
        }
        if (!stale.isEmpty()) template.opsForZSet().remove(index, stale.toArray());
    }

    private static void requireOwnedState(SeatInventorySnapshotDto state) {
        if (state.getReservationId() == null || state.getReservationId().isBlank()
                || state.getOrderNumber() == null || state.getUserId() == null) {
            throw new IllegalStateException("LOCKED/SOLD seat lacks order ownership: " + state.getSeatId());
        }
    }

    static void validateTradeState(Map<Long, SeatInventorySnapshotDto> stateBySeat) {
        for (SeatInventorySnapshotDto state : stateBySeat.values()) {
            if (Objects.equals(state.getSellStatus(), SellStatus.LOCK.getCode())
                    || Objects.equals(state.getSellStatus(), SellStatus.SOLD.getCode())) {
                requireOwnedState(state);
            }
        }
        lockedByReservation(stateBySeat).forEach(ReferenceSeatInventoryService::requireLockedGroup);
    }

    private static Map<String, List<SeatInventorySnapshotDto>> lockedByReservation(
            Map<Long, SeatInventorySnapshotDto> stateBySeat) {
        Map<String, List<SeatInventorySnapshotDto>> grouped = new LinkedHashMap<>();
        for (SeatInventorySnapshotDto state : stateBySeat.values()) {
            if (!Objects.equals(state.getSellStatus(), SellStatus.LOCK.getCode())) continue;
            String reservationId = state.getReservationId();
            if (reservationId == null || reservationId.isBlank()) {
                throw new IllegalStateException("LOCKED seat lacks reservation identity: " + state.getSeatId());
            }
            grouped.computeIfAbsent(reservationId, ignored -> new ArrayList<>()).add(state);
        }
        return grouped;
    }

    private static void requireLockedGroup(String intentId, List<SeatInventorySnapshotDto> locked) {
        if (intentId == null || intentId.isBlank()) {
            throw new IllegalStateException("LOCKED seat lacks reservation identity");
        }
        SeatInventorySnapshotDto first = locked.get(0);
        if (first.getTicketUserId() == null || first.getRequestFingerprint() == null
                || first.getRequestFingerprint().isBlank() || first.getReservationExpireTime() == null) {
            throw new IllegalStateException("LOCKED reservation metadata is incomplete: " + intentId);
        }
        Set<Long> ticketUsers = new HashSet<>();
        for (SeatInventorySnapshotDto state : locked) {
            requireOwnedState(state);
            if (state.getTicketUserId() == null
                    || !Objects.equals(state.getOrderNumber(), first.getOrderNumber())
                    || !Objects.equals(state.getUserId(), first.getUserId())
                    || !Objects.equals(state.getRequestFingerprint(), first.getRequestFingerprint())
                    || !Objects.equals(state.getReservationExpireTime(), first.getReservationExpireTime())) {
                throw new IllegalStateException("LOCKED reservation group is inconsistent: " + intentId);
            }
            if (!ticketUsers.add(state.getTicketUserId())) {
                throw new IllegalStateException("LOCKED reservation repeats ticket user: " + intentId);
            }
        }
    }

    static long expirationScore(SeatInventorySnapshotDto state) {
        if (state == null || state.getReservationExpireTime() == null) {
            throw new IllegalStateException("LOCKED reservation expiration is required");
        }
        return state.getReservationExpireTime().getTime();
    }

    private record TradeStateSnapshot(List<SeatVo> seats,
                                      Map<Long, SeatInventorySnapshotDto> stateBySeat) {
    }

    private String saleVersion(long programId, List<SeatVo> seats) {
        String canonical = seats.stream().sorted(java.util.Comparator.comparing(SeatVo::getId))
                .map(seat -> seat.getId() + ":" + seat.getTicketCategoryId() + ":"
                        + seat.getPrice().movePointRight(2).longValueExact())
                .collect(Collectors.joining("|", programId + "|", ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
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
                    && !Objects.equals(seat.getSellStatus(), SellStatus.LOCK.getCode())
                    && !Objects.equals(seat.getSellStatus(), SellStatus.SOLD.getCode())) {
                throw new IllegalStateException("bootstrap requires AVAILABLE, LOCKED or SOLD seats");
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
        return findCandidateSelection(programId, ticketCategoryId, requestedCount, candidateLimit, requestId).groups();
    }

    /**
     * ready、候选窗口、座位元数据和销售版本在同一个只读 Lua 中取得，避免同步 Redis 调用串行排队。
     */
    public CandidateSelection findCandidateSelection(long programId, long ticketCategoryId, int requestedCount,
                                                       int candidateLimit, String requestId) {
        validateCandidateRequest(requestedCount, candidateLimit, requestId);
        RedisTemplate<String, String> template = redisCache.getInstance();
        String raw = template.execute(findCandidatesScript, candidateKeys(programId, ticketCategoryId),
                candidateArgs(requestedCount, candidateLimit, requestId).toArray());
        return parseCandidateSelection(programId, requestedCount, raw);
    }

    /** 与其他独立 Redis 读取并行使用；脚本内的五个 key 仍属于同一销售分片。 */
    public Mono<CandidateSelection> findCandidateSelectionAsync(long programId, long ticketCategoryId,
                                                                 int requestedCount, int candidateLimit,
                                                                 String requestId) {
        validateCandidateRequest(requestedCount, candidateLimit, requestId);
        return reactiveRedisTemplate.execute(findCandidatesScript,
                        candidateKeys(programId, ticketCategoryId),
                        candidateArgs(requestedCount, candidateLimit, requestId))
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "candidate selection script returned no result")))
                .map(raw -> parseCandidateSelection(programId, requestedCount, raw));
    }

    private static void validateCandidateRequest(int requestedCount, int candidateLimit, String requestId) {
        if (requestedCount <= 0 || candidateLimit < requestedCount) {
            throw new IllegalArgumentException("candidateLimit must cover requestedCount");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required for deterministic candidate selection");
        }
    }

    private static List<String> candidateKeys(long programId, long ticketCategoryId) {
        return List.of(SeatReservationKeys.ready(programId), SeatReservationKeys.maintenance(programId),
                SeatReservationKeys.available(programId, ticketCategoryId), SeatReservationKeys.meta(programId),
                SeatReservationKeys.version(programId));
    }

    private static List<String> candidateArgs(int requestedCount, int candidateLimit, String requestId) {
        return List.of(String.valueOf(requestedCount), String.valueOf(candidateLimit),
                String.valueOf(Integer.toUnsignedLong(requestId.hashCode())),
                String.valueOf(TARGET_CANDIDATE_GROUPS), String.valueOf(CANDIDATE_PAGE_SIZE));
    }

    private CandidateSelection parseCandidateSelection(long programId, int requestedCount, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("candidate selection script returned no result");
        }
        JSONObject response = JSON.parseObject(raw);
        String status = response.getString("status");
        if ("NOT_READY".equals(status)) {
            throw new ReferenceInventoryNotReadyException(programId);
        }
        if ("MAINTENANCE".equals(status)) {
            throw new IllegalStateException("v5 seat inventory is being rebuilt");
        }
        if ("METADATA_MISSING".equals(status)) {
            throw new IllegalStateException("v5 seat metadata is incomplete for available candidates");
        }
        String saleVersion = response.getString("saleVersion");
        if ("INSUFFICIENT".equals(status)) {
            return new CandidateSelection(List.of(), saleVersion);
        }
        if (!"OK".equals(status)) {
            throw new IllegalStateException("unknown candidate selection status: " + status);
        }
        List<SeatVo> candidates = JSON.parseArray(JSON.toJSONString(response.get("seats")), SeatVo.class);
        List<List<SeatVo>> groups = buildCandidateGroups(
                candidates == null ? List.of() : candidates, requestedCount);
        return new CandidateSelection(groups, saleVersion);
    }

    public record CandidateSelection(List<List<SeatVo>> groups, String saleVersion) {
        public CandidateSelection {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
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
            throw new ReferenceInventoryNotReadyException(programId);
        }
        List<String> fields = seatIds.stream().map(String::valueOf).toList();
        List<Object> raw = template.opsForHash().multiGet(SeatReservationKeys.meta(programId),
                new ArrayList<Object>(fields));
        if (raw.size() != fields.size() || raw.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("seat metadata is incomplete");
        }
        return raw.stream().map(value -> JSON.parseObject(String.valueOf(value), SeatVo.class)).toList();
    }

    /** 选座展示读取静态元数据，并用 available/owner/sold 三组运行态 Key 覆盖销售状态。 */
    public List<SeatVo> listCurrentByCategory(long programId, long ticketCategoryId) {
        RedisTemplate<String, String> template = redisCache.getInstance();
        if (!Boolean.TRUE.equals(template.hasKey(SeatReservationKeys.ready(programId)))) {
            throw new IllegalStateException("v5 seat inventory is not initialized; fail closed");
        }
        List<SeatVo> seats = template.opsForHash().entries(SeatReservationKeys.meta(programId)).values().stream()
                .map(value -> JSON.parseObject(String.valueOf(value), SeatVo.class))
                .filter(seat -> Objects.equals(seat.getTicketCategoryId(), ticketCategoryId))
                .toList();
        applyCurrentState(template, programId, ticketCategoryId, seats);
        return seats.stream().sorted(java.util.Comparator.comparingInt(SeatVo::getRowCode)
                .thenComparingInt(SeatVo::getColCode)).toList();
    }

    public List<SeatVo> findCurrentByIds(long programId, List<Long> seatIds) {
        List<SeatVo> seats = new ArrayList<>(findByIds(programId, seatIds));
        RedisTemplate<String, String> template = redisCache.getInstance();
        seats.stream().collect(Collectors.groupingBy(SeatVo::getTicketCategoryId))
                .forEach((categoryId, categorySeats) ->
                        applyCurrentState(template, programId, categoryId, categorySeats));
        return seats;
    }

    private void applyCurrentState(RedisTemplate<String, String> template, long programId,
                                   long ticketCategoryId, List<SeatVo> seats) {
        Set<String> available = template.opsForZSet().range(
                SeatReservationKeys.available(programId, ticketCategoryId), 0, -1);
        Set<String> sold = template.opsForSet().members(SeatReservationKeys.sold(programId));
        Map<Object, Object> owners = template.opsForHash().entries(SeatReservationKeys.owner(programId));
        Set<String> availableIds = available == null ? Set.of() : available;
        Set<String> soldIds = sold == null ? Set.of() : sold;
        for (SeatVo seat : seats) {
            String seatId = String.valueOf(seat.getId());
            if (soldIds.contains(seatId)) {
                seat.setSellStatus(SellStatus.SOLD.getCode());
            } else if (owners.containsKey(seatId)) {
                seat.setSellStatus(SellStatus.LOCK.getCode());
            } else if (availableIds.contains(seatId)) {
                seat.setSellStatus(SellStatus.NO_SOLD.getCode());
            } else {
                throw new IllegalStateException("seat has no Redis sales state: " + seatId);
            }
        }
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
