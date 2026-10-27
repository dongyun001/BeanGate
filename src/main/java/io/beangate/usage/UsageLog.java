package io.beangate.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Schema owner for {@code usage_log} in the local/test profiles. Rows are written through
 * {@link UsageLogRepository} with JdbcTemplate batching, not through JPA; MySQL production schema is
 * created by Flyway.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "usage_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_request_id", columnNames = "request_id"),
        indexes = {
                @Index(name = "idx_usage_key_time", columnList = "api_key_id, created_at"),
                @Index(name = "idx_usage_model_time", columnList = "public_model, created_at")
        })
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Column(name = "key_prefix", length = 32)
    private String keyPrefix;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_name", length = 128)
    private String providerName;

    @Column(name = "public_model", length = 128)
    private String publicModel;

    @Column(name = "upstream_model", length = 128)
    private String upstreamModel;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "first_token_ms")
    private Long firstTokenMs;

    @Column(name = "is_stream", nullable = false)
    private boolean stream;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(nullable = false)
    private boolean estimated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
