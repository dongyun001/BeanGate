package io.beangate.web;

import org.springframework.http.HttpStatus;

/**
 * Gateway-side error that is rendered to callers using the OpenAI error envelope.
 * {@code failoverEligible} tells the router whether another provider may be attempted.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final String param;
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String message, String type, String code, String param,
                        Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message, "invalid_request_error", code, null, null);
    }

    public static ApiException forbidden(String message, String code) {
        return new ApiException(HttpStatus.FORBIDDEN, message, "invalid_request_error", code, null, null);
    }

    public static ApiException badRequest(String message, String code, String param) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, "invalid_request_error", code, param, null);
    }

    public static ApiException notFound(String message, String code) {
        return new ApiException(HttpStatus.NOT_FOUND, message, "invalid_request_error", code, null, null);
    }

    public static ApiException internal(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message, "server_error", "internal_error", null, null);
    }

    /** Maps an upstream failure onto the caller-facing envelope without leaking upstream status codes. */
    public static ApiException fromUpstream(int upstreamStatus, String upstreamMessage, Integer retryAfter) {
        String detail = upstreamMessage == null || upstreamMessage.isBlank() ? "" : ": " + upstreamMessage;
        if (upstreamStatus == 429) {
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "upstream provider is rate limiting" + detail, "rate_limit_error", "upstream_rate_limited",
                    null, retryAfter);
        }
        if (upstreamStatus == 401 || upstreamStatus == 403) {
            // The provider credential is broken: that is our configuration problem, not the caller's key.
            return new ApiException(HttpStatus.BAD_GATEWAY,
                    "upstream provider rejected the gateway credentials" + detail,
                    "upstream_error", "upstream_auth_error", null, null);
        }
        if (upstreamStatus == 400 || upstreamStatus == 404 || upstreamStatus == 422) {
            return new ApiException(HttpStatus.BAD_REQUEST,
                    "upstream rejected the request" + detail, "invalid_request_error", "upstream_rejected",
                    null, null);
        }
        if (upstreamStatus >= 500) {
            return new ApiException(HttpStatus.BAD_GATEWAY,
                    "upstream provider failed" + detail, "upstream_error", "upstream_error", null, null);
        }
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "unexpected upstream response" + detail, "upstream_error", "upstream_error", null, null);
    }

    public static ApiException upstreamUnavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message, "upstream_error", "upstream_unavailable",
                null, null);
    }

    public static ApiException upstreamTimeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "upstream provider timed out", "upstream_error",
                "upstream_timeout", null, null);
    }

    /** Failover is only safe for failures that did not produce a usable business response. */
    public boolean isFailoverEligible() {
        return status == HttpStatus.TOO_MANY_REQUESTS
                || status == HttpStatus.BAD_GATEWAY
                || status == HttpStatus.SERVICE_UNAVAILABLE
                || status == HttpStatus.GATEWAY_TIMEOUT;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public String getParam() {
        return param;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
