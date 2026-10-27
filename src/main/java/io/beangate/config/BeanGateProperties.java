package io.beangate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "beangate")
public record BeanGateProperties(Security security, Upstream upstream, Usage usage) {

    public record Security(String keyPepper, String encryptionKey, String adminToken) {
    }

    public record Upstream(long connectTimeoutMs, long responseTimeoutMs, long ttftTimeoutMs,
                           long streamIdleTimeoutMs) {
    }

    public record Usage(int queueCapacity, int batchSize, long flushIntervalMs) {
    }
}
