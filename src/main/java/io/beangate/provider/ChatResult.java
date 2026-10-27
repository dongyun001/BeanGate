package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** Successful upstream response. Body is kept as a tree to preserve unknown fields. */
public record ChatResult(JsonNode body, long latencyMs) {
}
