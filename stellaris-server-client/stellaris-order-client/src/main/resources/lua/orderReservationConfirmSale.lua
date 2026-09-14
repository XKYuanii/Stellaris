-- KEYS[1]=owner, KEYS[2]=reservation, KEYS[3]=result, KEYS[4]=sold SET,
-- KEYS[5]=final, KEYS[6]=expiration, KEYS[7]=shard expiration index
-- ARGV[1]=intentId, ARGV[2]=expiration member.
local intent_id = ARGV[1]
local final_state = redis.call('HGET', KEYS[5], intent_id)
if final_state == 'SOLD' then
    redis.call('ZREM', KEYS[7], ARGV[2])
    return '1'
end
if final_state == 'RELEASED' then
    redis.call('ZREM', KEYS[7], ARGV[2])
    return 'RELEASED'
end
local raw_reservation = redis.call('HGET', KEYS[2], intent_id)
if not raw_reservation then
    -- A sold transition must never acknowledge incomplete reservation state.
    return 'MISSING_RESERVATION'
end
local requested = cjson.decode(raw_reservation).seats
for _, selected in ipairs(requested) do
    local seat_id = tostring(selected.seatId)
    if redis.call('HGET', KEYS[1], seat_id) ~= intent_id then
        return 'OWNER_MISMATCH'
    end
end
for _, selected in ipairs(requested) do
    local seat_id = tostring(selected.seatId)
    redis.call('HDEL', KEYS[1], seat_id)
    redis.call('SADD', KEYS[4], seat_id)
end
redis.call('HDEL', KEYS[2], intent_id)
redis.call('HDEL', KEYS[3], intent_id)
redis.call('HSET', KEYS[5], intent_id, 'SOLD')
redis.call('ZREM', KEYS[6], intent_id)
redis.call('ZREM', KEYS[7], ARGV[2])
return '1'
