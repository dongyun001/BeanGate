package io.beangate.key;

import io.beangate.crypto.CryptoService;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "sk-gw-";
    private static final int DISPLAY_PREFIX_LENGTH = 14;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final ApiKeyRepository repository;
    private final CryptoService crypto;

    /**
     * Authentication happens on every proxied request, so the hash lookup is cached. The TTL bounds
     * how long a disabled key keeps working: 60 seconds is the documented revocation window.
     * Misses are not cached, so a flood of unknown keys cannot evict the hot entries.
     */
    private final Cache<String, ApiKey> byHash = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public ApiKeyService(ApiKeyRepository repository, CryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Transactional
    public CreatedKey create(String name, Set<String> allowedModels, Integer rpmLimit, Integer tpmLimit,
                             Integer dailyTokenLimit) {
        String plaintext = KEY_PREFIX + CryptoService.newSecret();
        ApiKey key = new ApiKey();
        key.setKeyHash(crypto.hmacSha256(plaintext));
        key.setKeyPrefix(plaintext.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, plaintext.length())));
        key.setName(name);
        key.setAllowedModels(allowedModels == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedModels));
        key.setRpmLimit(rpmLimit);
        key.setTpmLimit(tpmLimit);
        key.setDailyTokenLimit(dailyTokenLimit);
        key.setStatus(ApiKeyStatus.ENABLED);
        return new CreatedKey(repository.save(key), plaintext);
    }

    /**
     * Fail-closed: a key that cannot be resolved is rejected.
     *
     * <p>Deliberately not {@code @Transactional}: an outer transaction would borrow a JDBC connection
     * even when the cache hits, which costs milliseconds on every proxied request. The repository call
     * is transactional on its own when the cache misses.
     */
    public Optional<ApiKey> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank() || !presentedKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        String hash = crypto.hmacSha256(presentedKey.trim());
        ApiKey cached = byHash.getIfPresent(hash);
        if (cached != null) {
            return cached.getStatus() == ApiKeyStatus.ENABLED ? Optional.of(cached) : Optional.empty();
        }
        Optional<ApiKey> found = repository.findByKeyHash(hash)
                .filter(key -> key.getStatus() == ApiKeyStatus.ENABLED);
        found.ifPresent(key -> byHash.put(hash, key));
        return found;
    }

    @Transactional
    public ApiKey setStatus(Long id, ApiKeyStatus status) {
        ApiKey key = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("api key not found: " + id));
        key.setStatus(status);
        byHash.invalidate(key.getKeyHash());
        return key;
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findAll() {
        return repository.findAll();
    }

    public void invalidateAll() {
        byHash.invalidateAll();
    }

    /** The plaintext key is returned exactly once, at creation time. */
    public record CreatedKey(ApiKey apiKey, String plaintextKey) {
    }
}
