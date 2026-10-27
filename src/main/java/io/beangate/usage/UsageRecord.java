package io.beangate.usage;

/**
 * One completed gateway attempt. Persisted asynchronously from W3; until then it is only logged.
 * {@code estimated} is true when the upstream did not report usage and the counts are approximated.
 */
public record UsageRecord(
        String requestId,
        Long apiKeyId,
        String keyPrefix,
        Long providerId,
        String providerName,
        String publicModel,
        String upstreamModel,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long latencyMs,
        Long firstTokenMs,
        boolean stream,
        int httpStatus,
        String errorCode,
        boolean estimated) {
}
