package io.beangate.key;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "api_key", indexes = {
        @Index(name = "uk_api_key_hash", columnList = "key_hash", unique = true)
})
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** HMAC-SHA256(pepper, plaintext key), never the plaintext. */
    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    /** Safe display prefix, e.g. {@code sk-gw-AbCdEf12}. */
    @Column(name = "key_prefix", nullable = false, length = 32)
    private String keyPrefix;

    @Column(nullable = false, length = 128)
    private String name;

    /** Empty means "all enabled models"; non-empty is an explicit whitelist. */
    @Convert(converter = StringSetJsonConverter.class)
    @Column(name = "allowed_models", columnDefinition = "TEXT")
    private Set<String> allowedModels = new LinkedHashSet<>();

    @Column(name = "rpm_limit")
    private Integer rpmLimit;

    @Column(name = "tpm_limit")
    private Integer tpmLimit;

    @Column(name = "daily_token_limit")
    private Integer dailyTokenLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApiKeyStatus status = ApiKeyStatus.ENABLED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public boolean allowsModel(String model) {
        return allowedModels == null || allowedModels.isEmpty() || allowedModels.contains(model);
    }
}
