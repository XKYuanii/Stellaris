-- KEYS: lease ZSET, node-owner HASH
-- ARGV: node-id, process-owner-token, lease-ms
local owner = redis.call('HGET', KEYS[2], ARGV[1])
if not owner or owner ~= ARGV[2] then
    return 0
end
local redis_time = redis.call('TIME')
local now = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)
redis.call('ZADD', KEYS[1], 'XX', now + tonumber(ARGV[3]), ARGV[1])
return 1
