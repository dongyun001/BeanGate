package io.beangate.gateway;

import reactor.core.publisher.Flux;

/** Ready-to-write SSE frames for a streaming completion. */
public record StreamResponse(Flux<byte[]> frames) {
}
