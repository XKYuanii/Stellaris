-- KEYS[1]=dead-letter stream, KEYS[2]=source stream
-- ARGV[1]=dead record id, ARGV[2]=reservation id（Stream 兼容字段名 intentId）, ARGV[3]=payload
-- 重新入队与删除死信原记录原子完成，业务 eventId 保持不变。
redis.call('XADD', KEYS[2], '*', 'intentId', ARGV[2], 'payload', ARGV[3])
redis.call('XDEL', KEYS[1], ARGV[1])
return 1
