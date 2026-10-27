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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Streaming adapter for OpenAI-compatible upstreams.
 *
 * <p>The HTTP handshake is completed synchronously so that authentication failures, 429s and 5xx can
 * still trigger failover with a correct status code. Only after a 2xx is the body turned into frames,
 * so "first byte already written downstream" and "failover allowed" never overlap.
 */
@Component
public class OpenAiCompatStreamClient implements ProviderStreamClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatStreamClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final BeanGateProperties properties;
    private final CryptoService crypto;

    public OpenAiCompatStreamClient(WebClient.Builder builder, BeanGateProperties properties,
                                    CryptoService crypto) {
        this.properties = properties;
        this.crypto = crypto;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.upstream().connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.upstream().responseTimeoutMs()))
                // Streaming responses must not be buffered by the client's codec.
                .compress(false);

        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public boolean supports(Protocol protocol) {
        return protocol == Protocol.OPENAI_COMPAT || protocol == Protocol.OLLAMA;
    }

    @Override
    public StreamHandle open(Provider provider, JsonNode requestBody, String upstreamModel, String requestId,
                             String publicModel, boolean clientRequestedUsage) {
        // Timed from before the request so TTFT covers connect + TLS + upstream queueing, not just
        // the gap between response headers and the first body byte.
        StreamStats stats = new StreamStats();

        ObjectNode payload = requestBody.deepCopy();
        payload.put("model", upstreamModel);

        // Only ask for usage when the caller did not. If they did, their chunk is forwarded untouched.
        if (!clientRequestedUsage && provider.isSupportsStreamUsage()) {
            payload.putObject("stream_options").put("include_usage", true);
        }

        String url = trimTrailingSlash(provider.getBaseUrl()) + "/chat/completions";
        String credential = crypto.decrypt(provider.getApiKeyEncrypted());

        ResponseEntity<Flux<DataBuffer>> response;
        try {
            response = webClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                    .header("X-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, upstream -> upstream.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(errorBody -> Mono.error(ApiException.fromUpstream(
                                    upstream.statusCode().value(),
                                    OpenAiCompatProviderClient.extractMessage(errorBody),
                                    OpenAiCompatProviderClient.parseRetryAfter(
                                            upstream.headers().asHttpHeaders())))))
                    .toEntityFlux(DataBuffer.class)
                    .timeout(Duration.ofMillis(properties.upstream().responseTimeoutMs()))
                    .block();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (OpenAiCompatProviderClient.hasCause(e, TimeoutException.class)) {
                throw ApiException.upstreamTimeout();
            }
            log.warn("stream open failed for provider {}: {}", provider.getName(), e.toString());
            throw ApiException.upstreamUnavailable("cannot reach upstream provider");
        }

        if (response == null || response.getBody() == null) {
            throw ApiException.upstreamUnavailable("upstream returned no stream body");
        }

        SseFrameProcessor processor = new SseFrameProcessor(publicModel, !clientRequestedUsage, stats, MAPPER);

        Flux<byte[]> frames = response.getBody()
                .map(OpenAiCompatStreamClient::toBytes)
                .timeout(Mono.delay(Duration.ofMillis(properties.upstream().ttftTimeoutMs())),
                        item -> Mono.delay(Duration.ofMillis(properties.upstream().streamIdleTimeoutMs())))
                .concatMap(chunk -> Flux.fromIterable(processor.accept(chunk)))
                .concatWith(Flux.defer(() -> Flux.fromIterable(processor.flush())));

        return new StreamHandle(frames, stats);
    }

    private static byte[] toBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
