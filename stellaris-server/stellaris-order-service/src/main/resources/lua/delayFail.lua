-- KEYS: processing, pending, payload, attempts, dead
-- ARGV: task-id, now, max-attempts, retry-backoff-ms
local id = ARGV[1]
if redis.call('ZREM', KEYS[1], id) == 0 then return 'NOT_PROCESSING' end
local attempts = redis.call('HINCRBY', KEYS[4], id, 1)
if attempts >= tonumber(ARGV[3]) then
  redis.call('ZADD', KEYS[5], ARGV[2], id)
  return 'DEAD'
end
local delay = tonumber(ARGV[4]) * math.min(attempts, 10)
redis.call('ZADD', KEYS[2], tonumber(ARGV[2]) + delay, id)
return 'RETRY'
