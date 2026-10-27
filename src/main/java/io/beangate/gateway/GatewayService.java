package io.beangate.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.beangate.key.ApiKey;
import io.beangate.provider.ChatResult;
import io.beangate.provider.ProviderClient;
import io.beangate.provider.ProviderClients;
import io.beangate.provider.ProviderStreamClients;
import io.beangate.provider.StreamHandle;
import io.beangate.provider.StreamStats;
import io.beangate.provider.TokenUsage;
import io.beangate.ratelimit.RateLimitDecision;
import io.beangate.ratelimit.RateLimiter;
import io.beangate.route.ResolvedRoute;
import io.beangate.route.Router;
import io.beangate.usage.UsageRecord;
import io.beangate.usage.UsageRecorder;
import io.beangate.web.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * The two gateway flows.
 *
 * <p>Both validate the caller, resolve an ordered list of routes, and try them in order. Failover is
 * only allowed while nothing has been written downstream: for non-streaming calls that means before
 * returning the body, for streaming calls it means before the 2xx handshake completes.
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final Router router;
    private final ProviderClients clients;
    private final ProviderStreamClients streamClients;
    private final UsageRecorder usageRecorder;
    private final RateLimiter rateLimiter;

    public GatewayService(Router router, ProviderClients clients, ProviderStreamClients streamClients,
                          UsageRecorder usageRecorder, RateLimiter rateLimiter) {
        this.router = router;
        this.clients = clients;
        this.streamClients = streamClients;
        this.usageRecorder = usageRecorder;
        this.rateLimiter = rateLimiter;
    }

    public JsonNode chatCompletions(JsonNode body, ApiKey key, String requestId) {
        String publicModel = requireModel(body);
        requireAllowed(key, publicModel);
        enforceRateLimit(key);
        if (body.path("stream").asBoolean(false)) {
            throw ApiException.badRequest("use the streaming path for stream=true",
                    "unsupported_parameter", "stream");
        }
        List<ResolvedRoute> candidates = resolve(publicModel);

        ApiException lastFailure = null;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            ResolvedRoute route = candidates.get(attempt);
            boolean isLast = attempt == candidates.size() - 1;
            try {
                ProviderClient client = clients.forProtocol(route.provider().getProtocol());
                ChatResult result = client.call(route.provider(), body, route.upstreamModel(), requestId);

                JsonNode response = rewriteModel(result.body(), publicModel);
                recordSuccess(requestId, key, route, result, response);
                return response;
            } catch (ApiException e) {
                recordFailure(requestId, key, route, e, false);
                if (!e.isFailoverEligible() || isLast) {
                    throw e;
                }
                lastFailure = e;
                log.warn("request {} failed on provider {} ({} / {}), trying next route",
                        requestId, route.provider().getName(), e.getStatus().value(), e.getCode());
            }
        }
        throw lastFailure != null ? lastFailure : ApiException.internal("no route could serve the request");
    }

    public StreamResponse streamChatCompletions(JsonNode body, ApiKey key, String requestId) {
        String publicModel = requireModel(body);
        requireAllowed(key, publicModel);
        enforceRateLimit(key);
        List<ResolvedRoute> candidates = resolve(publicModel);

        boolean clientRequestedUsage = body.path("stream_options").path("include_usage").asBoolean(false);
        int promptEstimate = estimatePromptTokens(body);

        ApiException lastFailure = null;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            ResolvedRoute route = candidates.get(attempt);
            boolean isLast = attempt == candidates.size() - 1;
            try {
                StreamHandle handle = streamClients.forProtocol(route.provider().getProtocol())
                        .open(route.provider(), body, route.upstreamModel(), requestId, publicModel,
                                clientRequestedUsage);

                Flux<byte[]> frames = handle.frames().doFinally(signal ->
                        recordStream(requestId, key, route, handle.stats(), publicModel, promptEstimate, signal));

                return new StreamResponse(frames);
            } catch (ApiException e) {
                recordFailure(requestId, key, route, e, true);
                if (!e.isFailoverEligible() || isLast) {
                    throw e;
                }
                lastFailure = e;
                log.warn("stream request {} failed to open on provider {} ({} / {}), trying next route",
                        requestId, route.provider().getName(), e.getStatus().value(), e.getCode());
            }
        }
        throw lastFailure != null ? lastFailure : ApiException.internal("no route could serve the stream");
    }

    private String requireModel(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("request body must be a JSON object", "invalid_json", null);
        }
        JsonNode modelNode = body.get("model");
        if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            throw ApiException.badRequest("'model' is required", "missing_required_parameter", "model");
        }
        return modelNode.asText();
    }

    private static void requireAllowed(ApiKey key, String publicModel) {
        if (!key.allowsModel(publicModel)) {
            throw ApiException.forbidden(
                    "API key is not allowed to use model '" + publicModel + "'", "model_not_allowed");
        }
    }

    /**
     * Rejects with the gateway's own 429 before any upstream call, so a rate-limited key costs nothing
     * upstream. The limiter fails open internally if Redis is unavailable.
     */
    private void enforceRateLimit(ApiKey key) {
        RateLimitDecision decision = rateLimiter.check(key);
        if (!decision.allowed()) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "rate limit exceeded for this API key (" + decision.dimension() + ")",
                    "rate_limit_error", "rate_limit_exceeded", null, decision.retryAfterSeconds());
        }
    }

    private List<ResolvedRoute> resolve(String publicModel) {
        List<ResolvedRoute> candidates = router.resolve(publicModel);
        if (candidates.isEmpty()) {
            throw ApiException.notFound("model not found: " + publicModel, "model_not_found");
        }
        return candidates;
    }

    /** Callers asked for the public model name; every upstream would echo its own name back. */
    private static JsonNode rewriteModel(JsonNode upstreamBody, String publicModel) {
        if (upstreamBody instanceof ObjectNode object) {
            object.put("model", publicModel);
        }
        return upstreamBody;
    }

    private void recordSuccess(String requestId, ApiKey key, ResolvedRoute route, ChatResult result,
                               JsonNode response) {
        JsonNode usage = response.path("usage");
        Integer prompt = intOrNull(usage.get("prompt_tokens"));
        Integer completion = intOrNull(usage.get("completion_tokens"));
        Integer total = intOrNull(usage.get("total_tokens"));
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        usageRecorder.record(new UsageRecord(requestId, key.getId(), key.getKeyPrefix(),
                route.provider().getId(), route.provider().getName(),
                publicModelOf(response), route.upstreamModel(),
                prompt, completion, total, result.latencyMs(), null, false, 200, null, false));
    }

    private void recordStream(String requestId, ApiKey key, ResolvedRoute route, StreamStats stats,
                              String publicModel, int promptEstimate, SignalType signal) {
        TokenUsage reported = stats.usage();
        boolean estimated = reported == null;

        Integer prompt = reported != null && reported.prompt() != null ? reported.prompt() : promptEstimate;
        Integer completion = reported != null && reported.completion() != null
                ? reported.completion()
                : (int) Math.ceil(stats.completionChars() / 4.0);
        Integer total = reported != null && reported.total() != null
                ? reported.total()
                : (prompt == null ? null : prompt + completion);

        int status;
        String errorCode;
        switch (signal) {
            case ON_COMPLETE -> {
                status = 200;
                errorCode = null;
            }
            case CANCEL -> {
                // The caller hung up. This is normal for a chat UI, not a gateway failure.
                status = 499;
                errorCode = "client_closed";
            }
            default -> {
                status = 502;
                errorCode = "stream_error";
            }
        }

        usageRecorder.record(new UsageRecord(requestId, key.getId(), key.getKeyPrefix(),
                route.provider().getId(), route.provider().getName(), publicModel, route.upstreamModel(),
                prompt, completion, total, stats.totalMs(),
                stats.firstByteMs() < 0 ? null : stats.firstByteMs(),
                true, status, errorCode, estimated));
    }

    private void recordFailure(String requestId, ApiKey key, ResolvedRoute route, ApiException error,
                               boolean stream) {
        usageRecorder.record(new UsageRecord(requestId, key.getId(), key.getKeyPrefix(),
                route.provider().getId(), route.provider().getName(), null, route.upstreamModel(),
                null, null, null, 0L, null, stream, error.getStatus().value(), error.getCode(), false));
    }

    /** Rough prompt size for estimated metering when the upstream never reports usage. */
    private static int estimatePromptTokens(JsonNode body) {
        int chars = 0;
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.path("content");
                if (content.isTextual()) {
                    chars += content.asText().length();
                } else if (content.isArray()) {
                    for (JsonNode part : content) {
                        if (part.path("text").isTextual()) {
                            chars += part.path("text").asText().length();
                        }
                    }
                }
            }
        }
        return (int) Math.ceil(chars / 4.0);
    }

    private static String publicModelOf(JsonNode response) {
        JsonNode model = response.get("model");
        return model == null || !model.isTextual() ? null : model.asText();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asInt();
    }
}
