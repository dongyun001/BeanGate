package io.beangate.ratelimit;

import io.beangate.key.ApiKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when rate limiting is disabled (tests, or Redis deliberately not deployed). */
@Component
@ConditionalOnProperty(name = "beangate.rate-limit.enabled", havingValue = "false")
public class NoopRateLimiter implements RateLimiter, RateLimitTokenSink {

    @Override
    public RateLimitDecision check(ApiKey key) {
        return RateLimitDecision.allow();
    }

    @Override
    public void addTokens(Long apiKeyId, long tokens) {
        // no-op
    }
}
