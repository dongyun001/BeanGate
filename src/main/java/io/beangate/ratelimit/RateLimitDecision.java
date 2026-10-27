package io.beangate.ratelimit;

/** Result of an admission check. */
public record RateLimitDecision(boolean allowed, String dimension, int retryAfterSeconds) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, null, 0);
    }

    public static RateLimitDecision reject(String dimension, int retryAfterSeconds) {
        return new RateLimitDecision(false, dimension, Math.max(1, retryAfterSeconds));
    }
}
