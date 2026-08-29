-- KEYS: pending, processing, attempts, dead. ARGV: now, max, max-attempts, retry-backoff-ms
local ids = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
for _, id in ipairs(ids) do
  if redis.call('ZREM', KEYS[2], id) == 1 then
    local attempts = redis.call('HINCRBY', KEYS[3], id, 1)
    if attempts >= tonumber(ARGV[3]) then
      redis.call('ZADD', KEYS[4], ARGV[1], id)
    else
      local delay = tonumber(ARGV[4]) * math.min(attempts, 10)
      redis.call('ZADD', KEYS[1], tonumber(ARGV[1]) + delay, id)
    end
  end
end
return cjson.encode(ids)
