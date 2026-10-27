package io.beangate.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<OpenAiErrorResponse> handleApi(ApiException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus());
        if (e.getRetryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        }
        return builder.body(OpenAiErrorResponse.of(e));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class})
    public ResponseEntity<OpenAiErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(
                OpenAiErrorResponse.of("invalid request body: " + e.getMessage(),
                        "invalid_request_error", "invalid_json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<OpenAiErrorResponse> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                OpenAiErrorResponse.of("unknown endpoint: " + e.getResourcePath(),
                        "invalid_request_error", "unknown_endpoint"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled gateway error", e);
        return ResponseEntity.internalServerError().body(
                OpenAiErrorResponse.of("internal gateway error", "server_error", "internal_error"));
    }
}
