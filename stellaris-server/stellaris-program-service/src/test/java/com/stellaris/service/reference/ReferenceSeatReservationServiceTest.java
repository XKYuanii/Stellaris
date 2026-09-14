package com.stellaris.service.reference;

import com.alibaba.fastjson.JSON;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.enums.SellStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ReferenceSeatReservationServiceTest {

    @Test
    void validatesLockedTradeSnapshotBeforeRedisReset() {
        SeatInventorySnapshotDto locked = new SeatInventorySnapshotDto();
        locked.setSeatId(10L);
        locked.setTicketCategoryId(20L);
        locked.setPriceInCents(3000L);
        locked.setSellStatus(SellStatus.LOCK.getCode());
        locked.setReservationId("intent-1");
        locked.setOrderNumber(1001L);
        locked.setUserId(7L);
        locked.setTicketUserId(8L);
        locked.setRequestFingerprint("fingerprint");
        locked.setReservationExpireTime(new Date(123456789L));

        ReferenceSeatInventoryService.validateTradeState(Map.of(locked.getSeatId(), locked));

        assertThat(ReferenceSeatInventoryService.expirationScore(locked)).isEqualTo(123456789L);
        locked.setReservationId(null);
        assertThatIllegalStateException().isThrownBy(() ->
                ReferenceSeatInventoryService.validateTradeState(Map.of(locked.getSeatId(), locked)));
    }

    @Test
    void usesOneRedisClusterHashTagForAllProgramKeys() {
        assertThat(SeatReservationKeys.meta(123L)).contains("{sale:11}");
        assertThat(SeatReservationKeys.available(123L, 9L)).contains("{sale:11}");
        assertThat(SeatReservationKeys.owner(123L)).contains("{sale:11}");
        assertThat(SeatReservationKeys.reservation(123L)).contains("{sale:11}");
        assertThat(SeatReservationKeys.receipt(123L, "r1")).contains("{sale:11}");
        assertThat(SeatReservationKeys.eventStream(11)).contains("{sale:11}");
        assertThat(com.stellaris.domain.OrderReservationStreamKeys.expirationIndex(11)).contains("{sale:11}");
        assertThat(com.stellaris.domain.OrderReservationStreamKeys
                .parseExpirationMember("123|intent-1").programId()).isEqualTo(123L);
        assertThat(SeatReservationKeys.maintenance(123L)).contains("{sale:11}");
        assertThat(SeatReservationKeys.version(123L)).contains("{sale:11}");
    }

    @Test
    void rejectsDuplicateSeatBeforeRedisCall() {
        SeatReservationRequest request = new SeatReservationRequest("intent-1", 1L, 9L, 6,
                "fingerprint", "{\"eventId\":1}", System.currentTimeMillis() + 60_000, 86_400_000, 100_000, List.of(
                new SeatReservationRequest.Seat(10L, 1L, 10_000L, 1L),
                new SeatReservationRequest.Seat(10L, 1L, 10_000L, 2L)));

        assertThatIllegalArgumentException().isThrownBy(() -> ReferenceSeatReservationService.validate(request));
    }

    @Test
    void encodesRowAndColumnIntoStableZsetScore() {
        com.stellaris.vo.SeatVo seat = new com.stellaris.vo.SeatVo();
        seat.setRowCode(12);
        seat.setColCode(34);

        assertThat(ReferenceSeatInventoryService.score(seat)).isEqualTo(12_000_034D);
    }

    @Test
    void serializesReservationWithTheLuaContractFieldNames() {
        SeatReservationRequest.Seat seat = new SeatReservationRequest.Seat(1L, 5L, 48_800L, 9L);

        com.alibaba.fastjson.JSONArray payload = JSON.parseArray(
                ReferenceSeatReservationService.serializeSeats(List.of(seat)));

        assertThat(payload.getJSONObject(0).getLongValue("seatId")).isEqualTo(1L);
        assertThat(payload.getJSONObject(0).getLongValue("ticketCategoryId")).isEqualTo(5L);
        assertThat(payload.getJSONObject(0).getLongValue("priceInCents")).isEqualTo(48_800L);
        assertThat(payload.getJSONObject(0).getLongValue("ticketUserId")).isEqualTo(9L);
    }

    @Test
    void serializesSnowflakeIdsAsStringsForLuaWithoutPrecisionLoss() {
        SeatReservationRequest.Seat seat = new SeatReservationRequest.Seat(
                930000000000009849L, 900001L, 15_000L, 920000000000000000L);

        String json = ReferenceSeatReservationService.serializeSeats(List.of(seat));

        assertThat(json).contains("\"seatId\":\"930000000000009849\"")
                .contains("\"ticketCategoryId\":\"900001\"")
                .contains("\"ticketUserId\":\"920000000000000000\"")
                .contains("\"priceInCents\":15000");
    }

    @Test
    void metadataJsonRoundTripsSnowflakeIdsWithoutPrecisionLoss() {
        com.stellaris.vo.SeatVo seat = seat(930000000000009849L, 900001L, 12, 34,
                SellStatus.NO_SOLD.getCode());
        seat.setProgramId(900000L);
        seat.setPrice(new BigDecimal("150.00"));

        String json = ReferenceSeatInventoryService.metadataJson(seat);
        com.stellaris.vo.SeatVo restored = JSON.parseObject(json, com.stellaris.vo.SeatVo.class);

        assertThat(json).contains("\"id\":\"930000000000009849\"")
                .contains("\"programId\":\"900000\"")
                .contains("\"ticketCategoryId\":\"900001\"");
        assertThat(restored.getId()).isEqualTo(930000000000009849L);
        assertThat(restored.getProgramId()).isEqualTo(900000L);
        assertThat(restored.getTicketCategoryId()).isEqualTo(900001L);
    }

    @Test
    void failedReservationWithLegacyEmptyObjectDoesNotMaskBusinessCode() {
        SeatReservationResult result = ReferenceSeatReservationService.parseReservationResult(
                "{\"success\":false,\"replayed\":false,\"code\":\"SEAT_UNAVAILABLE\",\"seats\":{}}");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(result.seats()).isEmpty();
    }

    @Test
    void preheatSnapshotMustRejectDuplicateCoordinatesBeforeWritingRedis() {
        com.stellaris.vo.SeatVo first = seat(1L, 9L, 3, 5, SellStatus.NO_SOLD.getCode());
        com.stellaris.vo.SeatVo duplicateCoordinate = seat(2L, 9L, 3, 5, SellStatus.SOLD.getCode());

        assertThatIllegalStateException().isThrownBy(
                () -> ReferenceSeatInventoryService.validateSnapshot(List.of(first, duplicateCoordinate)));
    }

    @Test
    void buildsEveryAdjacentGroupWithoutCrossingRowGaps() {
        List<com.stellaris.vo.SeatVo> candidates = List.of(
                seat(1L, 9L, 1, 1, SellStatus.NO_SOLD.getCode()),
                seat(2L, 9L, 1, 2, SellStatus.NO_SOLD.getCode()),
                seat(3L, 9L, 1, 4, SellStatus.NO_SOLD.getCode()),
                seat(4L, 9L, 1, 5, SellStatus.NO_SOLD.getCode()),
                seat(5L, 9L, 2, 1, SellStatus.NO_SOLD.getCode()));

        List<List<com.stellaris.vo.SeatVo>> groups =
                ReferenceSeatInventoryService.buildCandidateGroups(candidates, 2);

        assertThat(groups).extracting(group -> group.stream().map(com.stellaris.vo.SeatVo::getId).toList())
                .containsExactly(List.of(1L, 2L), List.of(3L, 4L));
    }

    @Test
    void luaWritesIdempotencyReceiptOnlyAfterSeatChecksAndStreamAppend() throws Exception {
        String lua;
        try (var input = new ClassPathResource("lua/referenceReserveSeats.lua").getInputStream()) {
            lua = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(lua.indexOf("redis.call('SET', KEYS[11]")).isGreaterThan(lua.indexOf("redis.call('XADD', KEYS[8]"));
        assertThat(lua.indexOf("redis.call('SET', KEYS[11]")).isGreaterThan(lua.indexOf("SEAT_UNAVAILABLE"));
        assertThat(lua.indexOf("redis.call('XLEN', KEYS[8])")).isLessThan(lua.indexOf("redis.call('ZREM'"));
    }

    @Test
    void releaseLuaRejectsMissingOrMismatchedOwnersBeforeAnyMutation() throws Exception {
        String lua;
        try (var input = new ClassPathResource("lua/orderReservationRelease.lua").getInputStream()) {
            lua = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(lua).contains("return 'MISSING_RESERVATION'")
                .contains("return 'OWNER_MISMATCH'");
        assertThat(lua.indexOf("return 'OWNER_MISMATCH'"))
                .isLessThan(lua.indexOf("redis.call('XDEL'"));
    }

    private com.stellaris.vo.SeatVo seat(long id, long categoryId, int row, int column, int status) {
        com.stellaris.vo.SeatVo seat = new com.stellaris.vo.SeatVo();
        seat.setId(id);
        seat.setTicketCategoryId(categoryId);
        seat.setRowCode(row);
        seat.setColCode(column);
        seat.setSellStatus(status);
        seat.setPrice(BigDecimal.TEN);
        return seat;
    }
}
