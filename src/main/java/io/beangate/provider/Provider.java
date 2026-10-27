package io.beangate.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "provider")
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Protocol protocol;

    /** Includes the API version prefix, e.g. {@code https://api.deepseek.com/v1}. */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** AES-GCM ciphertext of the upstream credential. Never returned by any API. */
    @Column(name = "api_key_encrypted", nullable = false, length = 1024)
    private String apiKeyEncrypted;

    /** Tie-breaker when several routes share the same priority. Lower wins. */
    @Column(nullable = false)
    private int priority = 100;

    /**
     * Whether the upstream accepts {@code stream_options.include_usage}. Some OpenAI-compatible
     * implementations reject unknown request fields, so this is per-provider rather than assumed.
     */
    @Column(name = "supports_stream_usage", nullable = false)
    private boolean supportsStreamUsage = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
