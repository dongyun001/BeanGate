package io.beangate.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.beangate.key.ApiKey;
import io.beangate.route.Router;
import io.beangate.web.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public OpenAI-compatible surface. Clients point their existing SDK here unchanged. */
@RestController
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);
    private static final String TEXT_EVENT_STREAM_UTF8 = "text/event-stream;charset=UTF-8";

    private final GatewayService gatewayService;
    private final Router router;
    private final ObjectMapper objectMapper;

    public OpenAiController(GatewayService gatewayService, Router router, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /**
     * Single entry point for both modes. The response is written directly instead of using
     * {@code ResponseEntity<StreamingResponseBody>}: with a wildcard return type Spring MVC falls back
     * to the JSON message converters and cannot stream.
     */
    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public void chatCompletions(
            @RequestBody JsonNode body,
            @RequestAttribute(ApiKeyAuthFilter.API_KEY_ATTRIBUTE) ApiKey apiKey,
            @RequestAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE) String requestId,
            HttpServletResponse response) throws IOException {

        if (body.path("stream").asBoolean(false)) {
            writeStream(body, apiKey, requestId, response);
            return;
        }

        JsonNode result = gatewayService.chatCompletions(body, apiKey, requestId);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), result);
    }

    /**
     * Writes frames straight to the response. Closing the stream when the client hangs up is what
     * cancels the upstream subscription: Reactor propagates the cancel to the provider connection.
     */
    private void writeStream(JsonNode body, ApiKey apiKey, String requestId, HttpServletResponse response)
            throws IOException {
        // Opens the upstream stream (or throws) before a single byte is committed downstream, so
        // failover and error statuses still work.
        StreamResponse stream = gatewayService.streamChatCompletions(body, apiKey, requestId);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(TEXT_EVENT_STREAM_UTF8);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.flushBuffer();

        OutputStream out = response.getOutputStream();
        try (Stream<byte[]> frames = stream.frames().toStream()) {
            Iterator<byte[]> iterator = frames.iterator();
            while (iterator.hasNext()) {
                out.write(iterator.next());
                out.flush();
            }
        } catch (IOException e) {
            log.debug("request {}: client disconnected mid-stream", requestId);
        } catch (RuntimeException e) {
            log.warn("request {}: stream terminated abnormally: {}", requestId, e.toString());
        }
    }

    /** Lists only the models this key may call; provider names and upstream model names stay private. */
    @GetMapping(path = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> models(@RequestAttribute(ApiKeyAuthFilter.API_KEY_ATTRIBUTE) ApiKey apiKey) {
        List<Map<String, String>> data = router.enabledPublicModels().stream()
                .filter(apiKey::allowsModel)
                .map(model -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", model);
                    entry.put("object", "model");
                    entry.put("owned_by", "beangate");
                    return entry;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }
}
