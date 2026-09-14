-- KEYS[1]=meta, 2=owner, 3=reservation, 4=result, 5=final, 6=accountCount,
-- 7=expiration, 8=eventStream, 9=shard expiration index, 10..n=available ZSET
-- ARGV[1]=intentId, ARGV[2]=expiration member.
local intent_id = ARGV[1]
local final_state = redis.call('HGET', KEYS[5], intent_id)
if final_state == 'SOLD' then
    redis.call('ZREM', KEYS[9], ARGV[2])
    return 'SOLD'
end
if final_state == 'RELEASED' then
    redis.call('ZREM', KEYS[9], ARGV[2])
    return '1'
end
local raw_reservation = redis.call('HGET', KEYS[3], intent_id)
if not raw_reservation then
    -- Missing reservation data is only idempotent when no active-expiration evidence remains.
    -- Otherwise partial Redis corruption would be silently acknowledged and orphan the lock.
    if redis.call('ZSCORE', KEYS[7], intent_id)
            or redis.call('ZSCORE', KEYS[9], ARGV[2]) then
        return 'MISSING_RESERVATION'
    end
    redis.call('ZREM', KEYS[7], intent_id)
    redis.call('ZREM', KEYS[9], ARGV[2])
    return '1'
end
local available_by_category = {}
local reservation = cjson.decode(raw_reservation)
local requested = reservation.seats
for key_index = 10, #KEYS do
    local category = string.match(KEYS[key_index], 'available:(.+)$')
    available_by_category[category] = KEYS[key_index]
end
-- Validate the whole reservation before mutation so corrupt metadata cannot cause a partial release.
for _, selected in ipairs(requested) do
    local seat_id = tostring(selected.seatId)
    local category = tostring(selected.ticketCategoryId)
    if redis.call('HGET', KEYS[2], seat_id) ~= intent_id then
        return 'OWNER_MISMATCH'
    end
    if not redis.call('HGET', KEYS[1], seat_id) or not available_by_category[category] then
        return 'INVENTORY_CORRUPT'
    end
end
if reservation.streamId then
    redis.call('XDEL', KEYS[8], reservation.streamId)
end
for _, selected in ipairs(requested) do
    local seat_id = tostring(selected.seatId)
    local category = tostring(selected.ticketCategoryId)
    if redis.call('HGET', KEYS[2], seat_id) == intent_id then
        local raw_meta = redis.call('HGET', KEYS[1], seat_id)
        local available_key = available_by_category[category]
        if raw_meta and available_key then
            local meta = cjson.decode(raw_meta)
            redis.call('ZADD', available_key, meta.rowCode * 1000000 + meta.colCode, seat_id)
        end
        redis.call('HDEL', KEYS[2], seat_id)
    end
end
local remaining = redis.call('HINCRBY', KEYS[6], tostring(reservation.userId), -tonumber(reservation.ticketCount))
if remaining <= 0 then
    redis.call('HDEL', KEYS[6], tostring(reservation.userId))
end
redis.call('HDEL', KEYS[3], intent_id)
redis.call('HDEL', KEYS[4], intent_id)
redis.call('HSET', KEYS[5], intent_id, 'RELEASED')
redis.call('ZREM', KEYS[7], intent_id)
redis.call('ZREM', KEYS[9], ARGV[2])
return '1'
