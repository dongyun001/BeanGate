package io.beangate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.beangate.crypto.CryptoService;
import io.beangate.key.ApiKeyRepository;
import io.beangate.key.ApiKeyService;
import io.beangate.provider.Provider;
import io.beangate.provider.ProviderRepository;
import io.beangate.provider.Protocol;
import io.beangate.route.ModelRoute;
import io.beangate.route.ModelRouteRepository;
import java.io.IOException;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CHAT_RESPONSE = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"upstream-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":4,"total_tokens":15},
             "vendor_extension":{"kept":true}}
            """;

    private static final String STREAM_BODY =
            "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"model\":\"upstream-model\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}\n\n"
                    + "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"model\":\"upstream-model\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}\n\n"
                    + "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"model\":\"upstream-model\","
                    + "\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}\n\n"
                    + "data: [DONE]\n\n";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private ApiKeyRepository apiKeyRepository;
    @Autowired
    private ProviderRepository providerRepository;
    @Autowired
    private ModelRouteRepository routeRepository;
    @Autowired
    private CryptoService crypto;
    @Autowired
    private io.beangate.route.Router router;

    private MockWebServer upstream;

    @BeforeEach
    void setUp() throws IOException {
        routeRepository.deleteAll();
        providerRepository.deleteAll();
        apiKeyRepository.deleteAll();
        router.invalidateAll();
        upstream = new MockWebServer();
        upstream.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.shutdown();
    }

    @Test
    void streamsFramesWithPublicModelAndSuppressesInjectedUsageChunk() throws Exception {
        Provider provider = provider("deepseek", 10, true);
        route("chat-public", provider, "upstream-model", 10);
        String apiKey = newKey(null);
        upstream.enqueue(sseResponse());

        ResponseEntity<String> response = postStream(apiKey, """
                {"model":"chat-public","stream":true,"messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        assertThat(response.getBody()).contains("Hel", "lo", "[DONE]", "\"model\":\"chat-public\"");
        assertThat(response.getBody()).doesNotContain("upstream-model");
        // The usage-only chunk was injected by the gateway for metering and must not reach the client.
        assertThat(response.getBody()).doesNotContain("usage");

        RecordedRequest recorded = upstream.takeRequest();
        JsonNode forwarded = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(forwarded.path("model").asText()).isEqualTo("upstream-model");
        assertThat(forwarded.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void forwardsUsageChunkWhenClientExplicitlyAskedForIt() {
        Provider provider = provider("deepseek", 10, true);
        route("chat-public", provider, "upstream-model", 10);
        String apiKey = newKey(null);
        upstream.enqueue(sseResponse());

        ResponseEntity<String> response = postStream(apiKey, """
                {"model":"chat-public","stream":true,"stream_options":{"include_usage":true},
                 "messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("usage", "\"total_tokens\":9", "[DONE]");
    }

    @Test
    void doesNotInjectStreamOptionsWhenProviderDoesNotSupportIt() throws Exception {
        Provider provider = provider("strict-upstream", 10, false);
        route("chat-public", provider, "upstream-model", 10);
        String apiKey = newKey(null);
        upstream.enqueue(sseResponse());

        ResponseEntity<String> response = postStream(apiKey, """
                {"model":"chat-public","stream":true,"messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode forwarded = MAPPER.readTree(upstream.takeRequest().getBody().readUtf8());
        assertThat(forwarded.has("stream_options")).isFalse();
    }

    @Test
    void failsOverBeforeFirstStreamByteOnUpstreamError() {
        Provider primary = provider("primary", 10, true);
        Provider fallback = provider("fallback", 100, true);
        route("chat-public", primary, "upstream-model", 10);
        route("chat-public", fallback, "upstream-model", 100);
        String apiKey = newKey(null);

        upstream.enqueue(new MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"unavailable\"}}"));
        upstream.enqueue(sseResponse());

        ResponseEntity<String> response = postStream(apiKey, """
                {"model":"chat-public","stream":true,"messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("[DONE]");
        assertThat(upstream.getRequestCount()).isEqualTo(2);
    }

    @Test
    void adaptsAnthropicUpstreamToOpenAiContract() throws Exception {
        Provider provider = anthropicProvider("claude", 10);
        route("claude-public", provider, "claude-3-5-sonnet-latest", 10);
        String apiKey = newKey(null);
        upstream.enqueue(jsonResponse("""
                {"id":"msg_123","type":"message","role":"assistant","model":"claude-3-5-sonnet-latest",
                 "content":[{"type":"text","text":"hello from claude"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}
                """));

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"claude-public","temperature":1.5,"max_tokens":100,
                 "messages":[{"role":"system","content":"be nice"},{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("model").asText()).isEqualTo("claude-public");
        assertThat(response.getBody().path("object").asText()).isEqualTo("chat.completion");
        assertThat(response.getBody().path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("hello from claude");
        assertThat(response.getBody().path("usage").path("total_tokens").asInt()).isEqualTo(15);

        RecordedRequest recorded = upstream.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/anthropic/messages");
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("up-secret");
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(recorded.getHeader("Authorization")).isNull();

        JsonNode sent = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(sent.path("model").asText()).isEqualTo("claude-3-5-sonnet-latest");
        assertThat(sent.path("system").asText()).isEqualTo("be nice");
        assertThat(sent.path("temperature").asDouble()).isEqualTo(1.0d);
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(100);
        assertThat(sent.path("messages")).hasSize(1);
    }

    @Test
    void forwardsUnknownFieldsVerbatimAndRewritesModelBothWays() throws Exception {
        Provider provider = provider("deepseek", 10);
        route("chat-public", provider, "upstream-model", 10);
        String apiKey = newKey(null);
        upstream.enqueue(jsonResponse(CHAT_RESPONSE));

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"chat-public","messages":[{"role":"user","content":"hi"}],
                 "temperature":0.2,"logprobs":true,"some_future_field":{"x":1}}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("model").asText()).isEqualTo("chat-public");
        assertThat(response.getBody().path("vendor_extension").path("kept").asBoolean()).isTrue();
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();

        RecordedRequest recorded = upstream.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer up-secret");
        assertThat(recorded.getHeader("X-Request-Id")).isNotBlank();

        JsonNode forwarded = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(forwarded.path("model").asText()).isEqualTo("upstream-model");
        assertThat(forwarded.path("logprobs").asBoolean()).isTrue();
        assertThat(forwarded.path("some_future_field").path("x").asInt()).isEqualTo(1);
        assertThat(forwarded.path("messages").get(0).path("content").asText()).isEqualTo("hi");
    }

    @Test
    void failsOverToNextRouteOnUpstreamServerError() {
        Provider primary = provider("primary", 10);
        Provider fallback = provider("fallback", 100);
        route("chat-public", primary, "upstream-model", 10);
        route("chat-public", fallback, "upstream-model", 100);
        String apiKey = newKey(null);

        upstream.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"boom\"}}"));
        upstream.enqueue(jsonResponse(CHAT_RESPONSE));

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"chat-public","messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upstream.getRequestCount()).isEqualTo(2);
    }

    @Test
    void propagatesUpstreamRateLimitWithRetryAfterWhenNoFallback() {
        Provider provider = provider("primary", 10);
        route("chat-public", provider, "upstream-model", 10);
        String apiKey = newKey(null);

        upstream.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "7")
                .setBody("{\"error\":{\"message\":\"slow down\"}}"));

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"chat-public","messages":[{"role":"user","content":"hi"}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("upstream_rate_limited");
    }

    @Test
    void returns404ForUnknownModel() {
        String apiKey = newKey(null);

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"nope","messages":[]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("model_not_found");
    }

    @Test
    void rejectsMissingApiKey() {
        ResponseEntity<JsonNode> response = postChat(null, """
                {"model":"chat-public","messages":[]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("invalid_api_key");
    }

    @Test
    void rejectsDisabledApiKey() {
        String apiKey = newKey(null);
        apiKeyService.findAll().forEach(key -> apiKeyService.setStatus(
                key.getId(), io.beangate.key.ApiKeyStatus.DISABLED));

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"chat-public","messages":[]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsModelOutsideKeyWhitelist() {
        Provider provider = provider("deepseek", 10);
        route("chat-public", provider, "upstream-model", 10);
        String apiKey = newKey(Set.of("another-model"));
        upstream.enqueue(jsonResponse(CHAT_RESPONSE));

        ResponseEntity<JsonNode> response = postChat(apiKey, """
                {"model":"chat-public","messages":[]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("error").path("code").asText()).isEqualTo("model_not_allowed");
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void modelsEndpointOnlyListsModelsAllowedForTheKey() {
        Provider provider = provider("deepseek", 10);
        route("allowed-model", provider, "upstream-model", 10);
        route("hidden-model", provider, "upstream-model", 10);
        String apiKey = newKey(Set.of("allowed-model"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        ResponseEntity<JsonNode> response = rest.exchange(rest.getRootUri() + "/v1/models", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data")).hasSize(1);
        assertThat(response.getBody().path("data").get(0).path("id").asText()).isEqualTo("allowed-model");
        assertThat(response.getBody().path("data").get(0).path("object").asText()).isEqualTo("model");
        assertThat(response.getBody().path("object").asText()).isEqualTo("list");
    }

    private Provider provider(String name, int priority) {
        return provider(name, priority, true);
    }

    private Provider provider(String name, int priority, boolean supportsStreamUsage) {
        return provider(name, priority, supportsStreamUsage, upstream.url("/v1").toString());
    }

    private Provider anthropicProvider(String name, int priority) {
        return provider(name, priority, false, upstream.url("/anthropic").toString(), Protocol.ANTHROPIC);
    }

    private Provider provider(String name, int priority, boolean supportsStreamUsage, String baseUrl) {
        return provider(name, priority, supportsStreamUsage, baseUrl, Protocol.OPENAI_COMPAT);
    }

    private Provider provider(String name, int priority, boolean supportsStreamUsage, String baseUrl,
                              Protocol protocol) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        provider.setApiKeyEncrypted(crypto.encrypt("up-secret"));
        provider.setPriority(priority);
        provider.setEnabled(true);
        provider.setSupportsStreamUsage(supportsStreamUsage);
        return providerRepository.save(provider);
    }

    private void route(String publicModel, Provider provider, String upstreamModel, int priority) {
        ModelRoute route = new ModelRoute();
        route.setPublicModel(publicModel);
        route.setProvider(provider);
        route.setUpstreamModel(upstreamModel);
        route.setPriority(priority);
        route.setEnabled(true);
        routeRepository.save(route);
    }

    private String newKey(Set<String> allowedModels) {
        return apiKeyService.create("test-key", allowedModels, null, null, null).plaintextKey();
    }

    private ResponseEntity<JsonNode> postChat(String apiKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return rest.exchange(rest.getRootUri() + "/v1/chat/completions", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<String> postStream(String apiKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return rest.exchange(rest.getRootUri() + "/v1/chat/completions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse sseResponse() {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(STREAM_BODY);
    }
}
