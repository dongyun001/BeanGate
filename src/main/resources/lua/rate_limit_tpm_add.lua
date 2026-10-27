-- Adds consumed tokens to the current minute bucket.
-- KEYS[1] : current bucket
-- ARGV[1] : tokens
-- ARGV[2] : ttl (millis)
local key = KEYS[1]
redis.call('INCRBY', key, ARGV[1])
redis.call('PEXPIRE', key, ARGV[2])
return 1
