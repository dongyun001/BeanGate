package io.beangate.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** OpenAI-compatible error envelope: {@code {"error":{"message","type","param","code"}}}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OpenAiErrorResponse(Error error) {

    public record Error(String message, String type, String param, String code) {
    }

    public static OpenAiErrorResponse of(ApiException e) {
        return new OpenAiErrorResponse(new Error(e.getMessage(), e.getType(), e.getParam(), e.getCode()));
    }

    public static OpenAiErrorResponse of(String message, String type, String code) {
        return new OpenAiErrorResponse(new Error(message, type, null, code));
    }
}
