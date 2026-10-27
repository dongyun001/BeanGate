package io.beangate.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.beangate.config.BeanGateProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects {@code /admin/**} with a static admin token. These endpoints can mint API keys and read
 * every usage record, so they must never be reachable with only network-level trust.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter implements Ordered {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(BeanGateProperties properties, ObjectMapper objectMapper) {
        String token = properties.security().adminToken();
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(ADMIN_TOKEN_HEADER);
        boolean ok = expectedToken.length > 0
                && presented != null
                && MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getOutputStream(),
                    OpenAiErrorResponse.of("invalid or missing admin token", "invalid_request_error",
                            "invalid_admin_token"));
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
