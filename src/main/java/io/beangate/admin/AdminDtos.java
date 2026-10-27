package io.beangate.admin;

import io.beangate.key.ApiKey;
import io.beangate.key.ApiKeyService;
import io.beangate.key.ApiKeyStatus;
import io.beangate.provider.Provider;
import io.beangate.provider.Protocol;
import io.beangate.route.ModelRoute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Set;

/** Request/response shapes for the admin surface. Secrets never appear in responses. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateKeyRequest(
            @NotBlank String name,
            Set<String> allowedModels,
            @Positive Integer rpmLimit,
            @Positive Integer tpmLimit,
            @Positive Integer dailyTokenLimit) {
    }

    public record CreatedKeyResponse(
            Long id, String key, String keyPrefix, String name, Set<String> allowedModels,
            Integer rpmLimit, Integer tpmLimit, Integer dailyTokenLimit, String status, Instant createdAt) {

        public static CreatedKeyResponse from(ApiKeyService.CreatedKey created) {
            ApiKey key = created.apiKey();
            return new CreatedKeyResponse(key.getId(), created.plaintextKey(), key.getKeyPrefix(), key.getName(),
                    key.getAllowedModels(), key.getRpmLimit(), key.getTpmLimit(), key.getDailyTokenLimit(),
                    key.getStatus().name(), key.getCreatedAt());
        }
    }

    public record ApiKeyView(
            Long id, String keyPrefix, String name, Set<String> allowedModels,
            Integer rpmLimit, Integer tpmLimit, Integer dailyTokenLimit, String status, Instant createdAt) {

        public static ApiKeyView from(ApiKey key) {
            return new ApiKeyView(key.getId(), key.getKeyPrefix(), key.getName(), key.getAllowedModels(),
                    key.getRpmLimit(), key.getTpmLimit(), key.getDailyTokenLimit(),
                    key.getStatus().name(), key.getCreatedAt());
        }
    }

    public record UpdateKeyStatusRequest(@NotNull ApiKeyStatus status) {
    }

    public record CreateProviderRequest(
            @NotBlank String name,
            @NotNull Protocol protocol,
            @NotBlank String baseUrl,
            @NotBlank String apiKey,
            Integer priority,
            Boolean enabled,
            Boolean supportsStreamUsage) {
    }

    public record ProviderView(
            Long id, String name, String protocol, String baseUrl, int priority, boolean enabled,
            boolean supportsStreamUsage, boolean hasApiKey, Instant createdAt, Instant updatedAt) {

        public static ProviderView from(Provider provider) {
            return new ProviderView(provider.getId(), provider.getName(), provider.getProtocol().name(),
                    provider.getBaseUrl(), provider.getPriority(), provider.isEnabled(),
                    provider.isSupportsStreamUsage(),
                    provider.getApiKeyEncrypted() != null && !provider.getApiKeyEncrypted().isBlank(),
                    provider.getCreatedAt(), provider.getUpdatedAt());
        }
    }

    public record UpdateEnabledRequest(boolean enabled) {
    }

    public record CreateRouteRequest(
            @NotBlank String publicModel,
            @NotNull Long providerId,
            @NotBlank String upstreamModel,
            Integer priority,
            Boolean enabled) {
    }

    public record RouteView(
            Long id, String publicModel, Long providerId, String providerName,
            String upstreamModel, int priority, boolean enabled) {

        public static RouteView from(ModelRoute route) {
            return new RouteView(route.getId(), route.getPublicModel(), route.getProvider().getId(),
                    route.getProvider().getName(), route.getUpstreamModel(), route.getPriority(),
                    route.isEnabled());
        }
    }
}
