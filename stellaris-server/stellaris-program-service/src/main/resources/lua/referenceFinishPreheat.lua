-- KEYS[1]=ready, KEYS[2]=maintenance, KEYS[3]=version
-- ARGV[1]=seat count, ARGV[2]=new inventory version
if redis.call('GET', KEYS[2]) ~= 'PREHEATING' then
    return 0
end
redis.call('SET', KEYS[3], ARGV[2])
redis.call('SET', KEYS[1], ARGV[1])
redis.call('DEL', KEYS[2])
return 1
