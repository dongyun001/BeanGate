package io.beangate.provider;

import reactor.core.publisher.Flux;

/** An opened upstream stream: ready-to-write SSE frames plus the measurements collected while reading. */
public record StreamHandle(Flux<byte[]> frames, StreamStats stats) {
}
