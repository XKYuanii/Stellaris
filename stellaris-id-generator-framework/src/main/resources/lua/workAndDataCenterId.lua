-- KEYS: lease ZSET, node-owner HASH (same Redis Cluster hash tag)
-- ARGV: max-worker-id, max-data-center-id, lease-ms, process-owner-token
local lease_key = KEYS[1]
local owner_key = KEYS[2]
local max_worker_id = tonumber(ARGV[1])
local max_data_center_id = tonumber(ARGV[2])
local lease_ms = tonumber(ARGV[3])
local owner = ARGV[4]
local redis_time = redis.call('TIME')
local now = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)

-- 只有租约确实过期后才回收节点号；存活实例通过续租脚本延长该时间。
local expired = redis.call('ZRANGEBYSCORE', lease_key, '-inf', now)
for _, node_id in ipairs(expired) do
    redis.call('ZREM', lease_key, node_id)
    redis.call('HDEL', owner_key, node_id)
end

local capacity = (max_worker_id + 1) * (max_data_center_id + 1)
for node_id = 0, capacity - 1 do
    local node = tostring(node_id)
    if not redis.call('ZSCORE', lease_key, node) then
        local work_id = node_id % (max_worker_id + 1)
        local data_center_id = math.floor(node_id / (max_worker_id + 1))
        redis.call('HSET', owner_key, node, owner)
        redis.call('ZADD', lease_key, now + lease_ms, node)
        return string.format('{"workId":%d,"dataCenterId":%d}', work_id, data_center_id)
    end
end

return '{"workId":-1,"dataCenterId":-1}'
