package io.beangate.ratelimit;

import io.beangate.key.ApiKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis + Lua admission control.
 *
 * <p>RPM is exact: a sorted set holds one member per admitted request and the window is trimmed
 * inside the script. TPM is intentionally approximate: it counts tokens that have already completed,
 * so in-flight requests are invisible to it. That bound is the price of not knowing completion length
 * before forwarding (the reserve-then-reconcile variant is deferred).
 *
 * <p>Redis failures fail open: rate limiting is a protection, and an outage of the limiter must not
 * take down a working forwarding path. Failures are counted and logged at most once a minute.
 */
@Component
@ConditionalOnProperty(name = "beangate.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RedisRateLimiter implements RateLimiter, RateLimitTokenSink {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final long WINDOW_MS = 60_000L;
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> rpmScript;
    private final DefaultRedisScript<Long> tpmReadScript;
    private final DefaultRedisScript<Long> tpmAddScript;
    private final Counter rejectedCounter;
    private final Counter redisFailureCounter;
    private final AtomicLong lastFailureLogAt = new AtomicLong();

    public RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meterRegistry,
                            @Value("classpath:lua/rate_limit_rpm.lua") Resource rpmLua,
                            @Value("classpath:lua/rate_limit_tpm_read.lua") Resource tpmReadLua,
                            @Value("classpath:lua/rate_limit_tpm_add.lua") Resource tpmAddLua) {
        this.redis = redis;
        this.rpmScript = script(rpmLua, List.class);
        this.tpmReadScript = script(tpmReadLua, Long.class);
        this.tpmAddScript = script(tpmAddLua, Long.class);
        this.rejectedCounter = Counter.builder("beangate.ratelimit.rejected")
                .description("Requests rejected by the gateway rate limiter")
                .register(meterRegistry);
        this.redisFailureCounter = Counter.builder("beangate.ratelimit.redis.failures")
                .description("Rate limiter Redis failures (requests were allowed)")
                .register(meterRegistry);
    }

    @Override
    public RateLimitDecision check(ApiKey key) {
        long now = System.currentTimeMillis();
        try {
            if (key.getRpmLimit() != null && key.getRpmLimit() > 0) {
                List<?> result = redis.execute(rpmScript, List.of(rpmKey(key.getId())),
                        Long.toString(now), Long.toString(WINDOW_MS),
                        Integer.toString(key.getRpmLimit()), UUID.randomUUID().toString());
                if (result != null && !result.isEmpty() && toLong(result.get(0)) == 0L) {
                    int retryAfter = result.size() > 1 ? (int) toLong(result.get(1)) : 60;
                    rejectedCounter.increment();
                    return RateLimitDecision.reject("rpm", retryAfter);
                }
            }

            if (key.getTpmLimit() != null && key.getTpmLimit() > 0) {
                long bucket = now / WINDOW_MS;
                Long used = redis.execute(tpmReadScript,
                        List.of(tpmKey(key.getId(), bucket - 1), tpmKey(key.getId(), bucket)),
                        Long.toString(now), Long.toString(WINDOW_MS));
                if (used != null && used >= key.getTpmLimit()) {
                    long nextWindowMs = (bucket + 1) * WINDOW_MS;
                    int retryAfter = (int) Math.max(1, (nextWindowMs - now + 999) / 1000);
                    rejectedCounter.increment();
                    return RateLimitDecision.reject("tpm", retryAfter);
                }
            }

            return RateLimitDecision.allow();
        } catch (RuntimeException e) {
            failOpen(e);
            return RateLimitDecision.allow();
        }
    }

    @Override
    public void addTokens(Long apiKeyId, long tokens) {
        if (apiKeyId == null || tokens <= 0) {
            return;
        }
        try {
            long bucket = System.currentTimeMillis() / WINDOW_MS;
            redis.execute(tpmAddScript, List.of(tpmKey(apiKeyId, bucket)),
                    Long.toString(tokens), Long.toString(WINDOW_MS * 2));
        } catch (RuntimeException e) {
            failOpen(e);
        }
    }

    private void failOpen(RuntimeException e) {
        redisFailureCounter.increment();
        long now = System.currentTimeMillis();
        long last = lastFailureLogAt.get();
        if (now - last > FAILURE_LOG_INTERVAL_MS && lastFailureLogAt.compareAndSet(last, now)) {
            log.warn("rate limiter unavailable, allowing request (fail-open): {}", e.toString());
        }
    }

    private static String rpmKey(Long apiKeyId) {
        return "beangate:rl:rpm:" + apiKeyId;
    }

    private static String tpmKey(Long apiKeyId, long bucket) {
        return "beangate:rl:tpm:" + apiKeyId + ":" + bucket;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> DefaultRedisScript<T> script(Resource resource, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(resource);
        script.setResultType((Class) resultType);
        return script;
    }
}
