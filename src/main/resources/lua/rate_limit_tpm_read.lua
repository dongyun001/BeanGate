-- Approximate TPM usage over a sliding window using two adjacent minute buckets.
-- KEYS[1] : previous bucket
-- KEYS[2] : current bucket
-- ARGV[1] : now (epoch millis)
-- ARGV[2] : window size (millis)
-- returns weighted token count already consumed in the window
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local windowStart = math.floor(now / window) * window
local elapsedFraction = (now - windowStart) / window

local previous = tonumber(redis.call('GET', KEYS[1]) or '0')
local current = tonumber(redis.call('GET', KEYS[2]) or '0')

return math.floor(previous * (1 - elapsedFraction) + current + 0.5)
