-- Do not publish a result invalidated while it was being built, or after
-- another builder has acquired an expired lease.
if (redis.call('GET', KEYS[2]) or '0') ~= ARGV[1] then
    return 0
end
if redis.call('GET', KEYS[3]) ~= ARGV[2] then
    return 0
end
redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
return 1
