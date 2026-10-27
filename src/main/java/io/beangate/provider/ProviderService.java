package io.beangate.provider;

import io.beangate.crypto.CryptoService;
import io.beangate.route.Router;
import io.beangate.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderService {

    private final ProviderRepository repository;
    private final CryptoService crypto;
    private final Router router;

    public ProviderService(ProviderRepository repository, CryptoService crypto, Router router) {
        this.repository = repository;
        this.crypto = crypto;
        this.router = router;
    }

    @Transactional
    public Provider create(String name, Protocol protocol, String baseUrl, String plaintextApiKey,
                           Integer priority, Boolean enabled, Boolean supportsStreamUsage) {
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        Provider provider = new Provider();
        provider.setName(name);
        provider.setProtocol(protocol);
        provider.setBaseUrl(normalizedUrl);
        provider.setApiKeyEncrypted(crypto.encrypt(plaintextApiKey));
        provider.setPriority(priority == null ? 100 : priority);
        provider.setEnabled(enabled == null || enabled);
        provider.setSupportsStreamUsage(supportsStreamUsage == null || supportsStreamUsage);
        Provider saved = repository.save(provider);
        router.invalidateAll();
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Provider> findAll() {
        return repository.findAll();
    }

    @Transactional
    public Provider setEnabled(Long id, boolean enabled) {
        Provider provider = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("provider not found: " + id, "provider_not_found"));
        provider.setEnabled(enabled);
        provider.setUpdatedAt(Instant.now());
        router.invalidateAll();
        return provider;
    }

    /**
     * Only the scheme is validated here. Blocking private ranges must be profile-aware, because the
     * development Ollama provider legitimately lives on 127.0.0.1 (see the SSRF note in the design doc).
     */
    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        String scheme = trimmed.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            throw ApiException.badRequest("baseUrl must use http or https", "invalid_base_url", "baseUrl");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
