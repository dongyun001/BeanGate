-- Sliding-window RPM admission.
-- KEYS[1] : sorted set for this API key
-- ARGV[1] : now (epoch millis)
-- ARGV[2] : window size (millis)
-- ARGV[3] : limit
-- ARGV[4] : unique member id for this request
-- returns {1, 0} when admitted, {0, retryAfterSeconds} when rejected
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local used = redis.call('ZCARD', key)

if used >= limit then
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local retryAfter = math.ceil(window / 1000)
    if oldest[2] then
        retryAfter = math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))
    end
    return {0, retryAfter}
end

redis.call('ZADD', key, now, ARGV[4])
redis.call('PEXPIRE', key, window)
return {1, 0}
