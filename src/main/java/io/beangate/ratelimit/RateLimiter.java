package io.beangate.ratelimit;

import io.beangate.key.ApiKey;

/** Admission control for an API key. Implementations must be cheap to call on the request path. */
public interface RateLimiter {

    RateLimitDecision check(ApiKey key);
}
