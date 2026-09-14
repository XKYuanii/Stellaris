-- Replays an audited order event only while its original Redis reservation is still active.
-- The state check and XADD are atomic and all keys share the sale-shard hash tag.
-- KEYS[1]=reservation hash, 2=final-state hash, 3=owner hash,
-- 4=reservation expiration ZSET, 5=order event Stream.
-- ARGV[1]=intentId, 2=programId, 3=payload, 4=nowMillis.
local intent_id = ARGV[1]
local final_state = redis.call('HGET', KEYS[2], intent_id)
if final_state then
    return 'FINAL_' .. final_state
end

local raw_reservation = redis.call('HGET', KEYS[1], intent_id)
if not raw_reservation then
    return 'MISSING_RESERVATION'
end

local expires_at = redis.call('ZSCORE', KEYS[4], intent_id)
if not expires_at then
    return 'MISSING_EXPIRATION'
end
if tonumber(expires_at) <= tonumber(ARGV[4]) then
    return 'RESERVATION_EXPIRED'
end

local decode_ok, reservation = pcall(cjson.decode, raw_reservation)
if not decode_ok or not reservation.seats or #reservation.seats == 0 then
    return 'MALFORMED_RESERVATION'
end
for _, selected in ipairs(reservation.seats) do
    local seat_id = tostring(selected.seatId)
    if redis.call('HGET', KEYS[3], seat_id) ~= intent_id then
        return 'OWNER_MISMATCH'
    end
end

local stream_id = redis.call('XADD', KEYS[5], '*',
    'intentId', intent_id, 'programId', ARGV[2], 'payload', ARGV[3])
return 'OK:' .. stream_id
