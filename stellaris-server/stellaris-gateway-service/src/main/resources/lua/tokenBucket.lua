-- KEYS[1]: stellaris:rate:{route}:dimension:hashed-value
-- ARGV: capacity, refill-tokens, refill-period-ms, now-ms, ttl-ms
local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
local last_refill = tonumber(redis.call('HGET', KEYS[1], 'last_refill'))
if not tokens then
    tokens = capacity
    last_refill = now
end

local elapsed = math.max(0, now - last_refill)
local periods = math.floor(elapsed / refill_period)
if periods > 0 then
    tokens = math.min(capacity, tokens + periods * refill_tokens)
    last_refill = last_refill + periods * refill_period
end

local allowed = 0
local retry_after = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retry_after = math.max(1, refill_period - (now - last_refill))
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', last_refill)
redis.call('PEXPIRE', KEYS[1], ttl)
return tostring(allowed) .. ',' .. tostring(tokens) .. ',' .. tostring(retry_after)
