-- KEYS: pending ZSET, processing ZSET. ARGV: now, lease-until, max
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[3])
if #ids == 0 then
  return '[]'
end
for _, id in ipairs(ids) do
  if redis.call('ZREM', KEYS[1], id) == 1 then redis.call('ZADD', KEYS[2], ARGV[2], id) end
end
return cjson.encode(ids)
