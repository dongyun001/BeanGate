package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.beangate.config.BeanGateProperties;
import io.beangate.crypto.CryptoService;
import io.beangate.web.ApiException;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Forwards to any OpenAI-compatible {@code POST {baseUrl}/chat/completions}.
 *
 * <p>Deliberately sends the caller's JSON tree (with only {@code model} rewritten) instead of binding
 * to a typed DTO: the OpenAI schema only grows, and DTO mapping silently drops unknown fields.
 */
@Component
public class OpenAiCompatProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProviderClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_UPSTREAM_MESSAGE = 300;

    private final WebClient webClient;
    private final BeanGateProperties properties;
    private final CryptoService crypto;

    public OpenAiCompatProviderClient(WebClient.Builder builder, BeanGateProperties properties,
                                      CryptoService crypto) {
        this.properties = properties;
        this.crypto = crypto;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.upstream().connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.upstream().responseTimeoutMs()));

        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public boolean supports(Protocol protocol) {
        return protocol == Protocol.OPENAI_COMPAT || protocol == Protocol.OLLAMA;
    }

    @Override
    public ChatResult call(Provider provider, JsonNode requestBody, String upstreamModel, String requestId) {
        com.fasterxml.jackson.databind.node.ObjectNode payload = requestBody.deepCopy();
        payload.put("model", upstreamModel);

        String url = trimTrailingSlash(provider.getBaseUrl()) + "/chat/completions";
        String credential = crypto.decrypt(provider.getApiKeyEncrypted());
        long startedAt = System.nanoTime();

        try {
            return webClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                    .header("X-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(JsonNode.class)
                                    .switchIfEmpty(Mono.error(
                                            ApiException.upstreamUnavailable("upstream returned an empty body")))
                                    .map(body -> new ChatResult(body, elapsedMs(startedAt)));
                        }
                        Integer retryAfter = parseRetryAfter(response.headers().asHttpHeaders());
                        int status = response.statusCode().value();
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errorBody -> Mono.error(
                                        ApiException.fromUpstream(status, extractMessage(errorBody), retryAfter)));
                    })
                    .timeout(Duration.ofMillis(properties.upstream().responseTimeoutMs()))
                    .block();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (hasCause(e, TimeoutException.class)) {
                throw ApiException.upstreamTimeout();
            }
            log.warn("upstream call failed for provider {}: {}", provider.getName(), e.toString());
            throw ApiException.upstreamUnavailable("cannot reach upstream provider");
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    static Integer parseRetryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null; // HTTP-date form: let the client retry on its own schedule
        }
    }

    /** Pulls the human-readable message out of an OpenAI/Anthropic-ish error body, truncated. */
    static String extractMessage(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            Optional<String> message = Optional.ofNullable(node.path("error").path("message").textValue())
                    .or(() -> Optional.ofNullable(node.path("message").textValue()))
                    .or(() -> Optional.ofNullable(node.path("error").path("type").textValue()));
            if (message.isPresent() && !message.get().isBlank()) {
                return truncate(message.get());
            }
        } catch (Exception ignored) {
            // not JSON; fall through to the raw body
        }
        return truncate(rawBody.strip().replaceAll("\\s+", " "));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_UPSTREAM_MESSAGE ? value : value.substring(0, MAX_UPSTREAM_MESSAGE) + "...";
    }

    static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
