-- BeanGate schema. MySQL 8. Approximate types are chosen so the same columns work in H2's MySQL
-- mode (used by tests), where the schema comes from the JPA entities instead.

CREATE TABLE IF NOT EXISTS provider (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key_encrypted VARCHAR(1024) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    supports_stream_usage BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS model_route (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_model VARCHAR(128) NOT NULL,
    provider_id BIGINT NOT NULL,
    upstream_model VARCHAR(128) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_route_model_provider UNIQUE (public_model, provider_id),
    CONSTRAINT fk_route_provider FOREIGN KEY (provider_id) REFERENCES provider (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS api_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    key_hash VARCHAR(64) NOT NULL,
    key_prefix VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    allowed_models TEXT NULL,
    rpm_limit INT NULL,
    tpm_limit INT NULL,
    daily_token_limit INT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS usage_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    api_key_id BIGINT NULL,
    key_prefix VARCHAR(32) NULL,
    provider_id BIGINT NULL,
    provider_name VARCHAR(128) NULL,
    public_model VARCHAR(128) NULL,
    upstream_model VARCHAR(128) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    first_token_ms BIGINT NULL,
    is_stream BOOLEAN NOT NULL DEFAULT FALSE,
    http_status INT NOT NULL,
    error_code VARCHAR(64) NULL,
    estimated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_usage_request_id UNIQUE (request_id),
    INDEX idx_usage_key_time (api_key_id, created_at),
    INDEX idx_usage_model_time (public_model, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
