package io.beangate.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.beangate.crypto.CryptoService;
import io.beangate.key.ApiKeyRepository;
import io.beangate.key.ApiKeyService;
import io.beangate.provider.Provider;
import io.beangate.provider.ProviderRepository;
import io.beangate.provider.Protocol;
import io.beangate.route.ModelRoute;
import io.beangate.route.ModelRouteRepository;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Real Redis, real Lua: the third request inside a two-per-minute window must be rejected by the
 * gateway before it reaches the upstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("beangate.rate-limit.enabled", () -> true);
    }

    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    private static final String CHAT_RESPONSE = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"upstream-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

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
    void rejectsRequestsPastTheRpmLimitWithoutTouchingTheUpstream() {
        Provider provider = new Provider();
        provider.setName("upstream");
        provider.setProtocol(Protocol.OPENAI_COMPAT);
        provider.setBaseUrl(upstream.url("/v1").toString());
        provider.setApiKeyEncrypted(crypto.encrypt("up-secret"));
        provider.setEnabled(true);
        providerRepository.save(provider);

        ModelRoute route = new ModelRoute();
        route.setPublicModel("chat-public");
        route.setProvider(provider);
        route.setUpstreamModel("upstream-model");
        routeRepository.save(route);

        String apiKey = apiKeyService.create("rate-limited", null, 2, null, null).plaintextKey();
        upstream.enqueue(jsonResponse());
        upstream.enqueue(jsonResponse());

        assertThat(post(apiKey).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(apiKey).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> rejected = post(apiKey);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody().path("error").path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(upstream.getRequestCount()).isEqualTo(2);
    }

    private ResponseEntity<JsonNode> post(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return rest.exchange(rest.getRootUri() + "/v1/chat/completions", HttpMethod.POST,
                new HttpEntity<>("""
                        {"model":"chat-public","messages":[{"role":"user","content":"hi"}]}
                        """, headers), JsonNode.class);
    }

    private static MockResponse jsonResponse() {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(CHAT_RESPONSE);
    }
}
