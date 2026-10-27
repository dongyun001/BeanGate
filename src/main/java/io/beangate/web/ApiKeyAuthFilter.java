package io.beangate.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.beangate.key.ApiKey;
import io.beangate.key.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /v1/**} calls with {@code Authorization: Bearer sk-gw-...}.
 *
 * <p>Fail-closed is the intended behaviour when the key store (Redis, in later weeks) is unavailable:
 * we never let an unverifiable key through, except via the short-TTL positive cache added in W3.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter implements Ordered {

    public static final String API_KEY_ATTRIBUTE = "beangate.apiKey";
    public static final String REQUEST_ID_ATTRIBUTE = "beangate.requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

        try {
            String presented = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (presented == null) {
                writeError(response, ApiException.unauthorized("invalid_api_key",
                        "missing Authorization header; expected 'Bearer sk-gw-...'"));
                return;
            }

            Optional<ApiKey> key = apiKeyService.authenticate(presented);
            if (key.isEmpty()) {
                log.debug("rejected api key with prefix {}", safePrefix(presented));
                writeError(response, ApiException.unauthorized("invalid_api_key",
                        "invalid, disabled or unknown API key"));
                return;
            }

            request.setAttribute(API_KEY_ATTRIBUTE, key.get());
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /** Never log the full key. */
    private static String safePrefix(String key) {
        return key.length() <= 14 ? key : key.substring(0, 14) + "...";
    }

    private void writeError(HttpServletResponse response, ApiException error) throws IOException {
        response.setStatus(error.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (error.getRetryAfterSeconds() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(error.getRetryAfterSeconds()));
        }
        objectMapper.writeValue(response.getOutputStream(), OpenAiErrorResponse.of(error));
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
