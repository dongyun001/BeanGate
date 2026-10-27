package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.beangate.config.BeanGateProperties;
import io.beangate.crypto.CryptoService;
import io.beangate.web.ApiException;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Non-streaming Anthropic adapter ({@code POST {baseUrl}/messages}).
 *
 * <p>Anthropic authenticates with {@code x-api-key} plus a required {@code anthropic-version} header
 * instead of a bearer token, and its payload is a different shape entirely; all of that is confined
 * to {@link AnthropicProtocolConverter} and this class.
 */
@Component
public class AnthropicProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProviderClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final BeanGateProperties properties;
    private final CryptoService crypto;

    public AnthropicProviderClient(WebClient.Builder builder, BeanGateProperties properties, CryptoService crypto) {
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
        return protocol == Protocol.ANTHROPIC;
    }

    @Override
    public ChatResult call(Provider provider, JsonNode requestBody, String upstreamModel, String requestId) {
        ObjectNode payload = AnthropicProtocolConverter.toAnthropicRequest(requestBody, upstreamModel);
        String url = trimTrailingSlash(provider.getBaseUrl()) + "/messages";
        String credential = crypto.decrypt(provider.getApiKeyEncrypted());
        long startedAt = System.nanoTime();

        try {
            JsonNode anthropicResponse = webClient.post()
                    .uri(url)
                    .header("x-api-key", credential)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(JsonNode.class)
                                    .switchIfEmpty(Mono.error(
                                            ApiException.upstreamUnavailable("upstream returned an empty body")));
                        }
                        Integer retryAfter = OpenAiCompatProviderClient.parseRetryAfter(
                                response.headers().asHttpHeaders());
                        int status = response.statusCode().value();
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errorBody -> Mono.error(ApiException.fromUpstream(status,
                                        OpenAiCompatProviderClient.extractMessage(errorBody), retryAfter)));
                    })
                    .timeout(Duration.ofMillis(properties.upstream().responseTimeoutMs()))
                    .block();

            if (anthropicResponse == null) {
                throw ApiException.upstreamUnavailable("upstream returned an empty body");
            }
            long latency = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return new ChatResult(AnthropicProtocolConverter.toOpenAiResponse(anthropicResponse, upstreamModel),
                    latency);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (OpenAiCompatProviderClient.hasCause(e, TimeoutException.class)) {
                throw ApiException.upstreamTimeout();
            }
            log.warn("anthropic call failed for provider {}: {}", provider.getName(), e.toString());
            throw ApiException.upstreamUnavailable("cannot reach upstream provider");
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
