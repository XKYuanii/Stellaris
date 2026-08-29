-- KEYS[1]=ready, 2=maintenance, 3=meta, 4=owner, 5=reservation, 6=result,
-- 7=accountCount, 8=eventStream, 9=final, 10=expiration, 11=idempotency receipt,
-- 12..n=available ZSETs.
-- ARGV[1]=intentId, 2=seatsJson, 3=eventPayload, 4=userId, 5=accountLimit,
-- 6=requestFingerprint, 7=expireAtMillis, 8=receiptTtlMillis.
local intent_id = ARGV[1]
local requested = cjson.decode(ARGV[2])
local event_payload = ARGV[3]
local user_id = ARGV[4]
local account_limit = tonumber(ARGV[5]) or 0
local request_fingerprint = ARGV[6]
local empty_seats = cjson.decode('[]')

local raw_receipt = redis.call('GET', KEYS[11])
if raw_receipt then
    local receipt = cjson.decode(raw_receipt)
    if receipt.requestFingerprint ~= request_fingerprint then
        return cjson.encode({success=false, replayed=true, code='IDEMPOTENCY_CONFLICT', seats=empty_seats})
    end
    local replay_result = redis.call('HGET', KEYS[6], intent_id)
    local replay_seats = replay_result and cjson.decode(replay_result) or empty_seats
    return cjson.encode({success=true, replayed=true, code='OK', seats=replay_seats})
end

local previous = redis.call('HGET', KEYS[6], intent_id)
if previous then
    local previous_reservation = redis.call('HGET', KEYS[5], intent_id)
    if not previous_reservation or cjson.decode(previous_reservation).requestFingerprint ~= request_fingerprint then
        return cjson.encode({success=false, replayed=true, code='IDEMPOTENCY_CONFLICT', seats=empty_seats})
    end
    return cjson.encode({success=true, replayed=true, code='OK', seats=cjson.decode(previous)})
end

local final_state = redis.call('HGET', KEYS[9], intent_id)
if final_state then
    return cjson.encode({success=false, replayed=true, code='RESERVATION_' .. final_state, seats=empty_seats})
end

if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 1 then
    return cjson.encode({success=false, replayed=false, code='INVENTORY_NOT_READY', seats=empty_seats})
end

local purchased = tonumber(redis.call('HGET', KEYS[7], user_id) or '0')
if account_limit > 0 and purchased + #requested > account_limit then
    return cjson.encode({success=false, replayed=false, code='ACCOUNT_LIMIT_EXCEEDED', seats=empty_seats})
end

local available_by_category = {}
for key_index = 12, #KEYS do
    local category = string.match(KEYS[key_index], 'available:(.+)$')
    available_by_category[category] = KEYS[key_index]
end

local seen = {}
local snapshots = {}
for _, selected in ipairs(requested) do
    local seat_id = tostring(selected.seatId)
    local category = tostring(selected.ticketCategoryId)
    if seen[seat_id] then
        return cjson.encode({success=false, replayed=false, code='DUPLICATE_SEAT', seats=empty_seats})
    end
    seen[seat_id] = true

    local available_key = available_by_category[category]
    local owner = redis.call('HGET', KEYS[4], seat_id)
    if owner and owner ~= intent_id then
        return cjson.encode({success=false, replayed=false, code='SEAT_OWNED', seats=empty_seats})
    end
    if not available_key or not redis.call('ZSCORE', available_key, seat_id) then
        return cjson.encode({success=false, replayed=false, code='SEAT_UNAVAILABLE', seats=empty_seats})
    end
    local raw_meta = redis.call('HGET', KEYS[3], seat_id)
    if not raw_meta then
        return cjson.encode({success=false, replayed=false, code='SEAT_META_MISSING', seats=empty_seats})
    end
    local snapshot = cjson.decode(raw_meta)
    if tostring(snapshot.ticketCategoryId) ~= category or tonumber(snapshot.priceInCents) ~= tonumber(selected.priceInCents) then
        return cjson.encode({success=false, replayed=false, code='SEAT_PRICE_OR_CATEGORY_CHANGED', seats=empty_seats})
    end
    snapshot.ticketUserId = selected.ticketUserId
    table.insert(snapshots, snapshot)
end

for _, selected in ipairs(requested) do
    local seat_id = tostring(selected.seatId)
    local available_key = available_by_category[tostring(selected.ticketCategoryId)]
    redis.call('ZREM', available_key, seat_id)
    redis.call('HSET', KEYS[4], seat_id, intent_id)
end
-- XADD 和锁座变更位于同一 Lua 原子操作：进程在脚本返回前被 kill，也不会只锁座不留事件。
local stream_id = redis.call('XADD', KEYS[8], '*', 'intentId', intent_id, 'payload', event_payload)
local reservation = {userId=user_id, ticketCount=#requested, seats=requested,
                     requestFingerprint=request_fingerprint, eventPayload=event_payload, streamId=stream_id}
redis.call('HSET', KEYS[5], intent_id, cjson.encode(reservation))
redis.call('HSET', KEYS[6], intent_id, cjson.encode(snapshots))
redis.call('SET', KEYS[11], cjson.encode({requestFingerprint=request_fingerprint,
                                         eventPayload=event_payload}),
           'PX', tonumber(ARGV[8]))
redis.call('HINCRBY', KEYS[7], user_id, #requested)
redis.call('ZADD', KEYS[10], tonumber(ARGV[7]), intent_id)
return cjson.encode({success=true, replayed=false, code='OK', seats=snapshots})
