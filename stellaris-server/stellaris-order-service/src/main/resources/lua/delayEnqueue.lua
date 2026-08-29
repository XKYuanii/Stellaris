-- KEYS: pending, processing, payload, attempts, dead. ARGV: task-id, due-ms, payload
-- 数据库兜底扫描可能反复发现同一订单；不得覆盖正在处理的 lease、已有到期时间或 DEAD 任务。
if redis.call('ZSCORE', KEYS[5], ARGV[1]) then return 'DEAD' end
if redis.call('ZSCORE', KEYS[2], ARGV[1]) then return 'PROCESSING' end
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then return 'PENDING' end
redis.call('HSET', KEYS[3], ARGV[1], ARGV[3])
redis.call('HSET', KEYS[4], ARGV[1], 0)
redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
return 'ENQUEUED'
