-- KEYS[1]=ready, KEYS[2]=maintenance, KEYS[3]=owner, KEYS[4]=reservation
-- Atomically closes new reservations only when there are no active owners/reservations.
if redis.call('HLEN', KEYS[3]) > 0 or redis.call('HLEN', KEYS[4]) > 0 then
    return 0
end
-- dataPreheat 外层持有节目维度分布式锁。上次进程在关闸后退出时 ready 已不存在，
-- 新执行者可接管并从完整快照重建；若 ready 仍存在则视为冲突维护，拒绝覆盖。
if redis.call('EXISTS', KEYS[2]) == 1 then
    if redis.call('EXISTS', KEYS[1]) == 0 then
        redis.call('SET', KEYS[2], 'PREHEATING')
        return 2
    end
    return -1
end
redis.call('SET', KEYS[2], 'PREHEATING')
redis.call('DEL', KEYS[1])
return 1
