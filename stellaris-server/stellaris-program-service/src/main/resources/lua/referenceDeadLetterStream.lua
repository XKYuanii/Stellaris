-- KEYS[1]=source stream, KEYS[2]=dead-letter stream
-- ARGV[1]=consumer group, ARGV[2]=record id, ARGV[3]=reservation id（兼容字段名 intentId）, ARGV[4]=payload, ARGV[5]=reason
-- 写死信、确认原消息和删除原记录在同一 Redis 原子操作中完成。
redis.call('XADD', KEYS[2], '*',
        'sourceId', ARGV[2], 'intentId', ARGV[3], 'payload', ARGV[4], 'reason', ARGV[5])
redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
redis.call('XDEL', KEYS[1], ARGV[2])
return 1
