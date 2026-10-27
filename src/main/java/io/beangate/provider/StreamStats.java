package io.beangate.provider;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable per-stream measurements, filled by the frame processor and read once the stream ends.
 * A stream can terminate by completing, erroring, or being cancelled by the client; all three must
 * still produce a usage record.
 */
public final class StreamStats {

    private final long startedAtNanos = System.nanoTime();
    private final AtomicLong firstChunkAtNanos = new AtomicLong();
    private final AtomicReference<TokenUsage> usage = new AtomicReference<>();
    private final AtomicLong completionChars = new AtomicLong();

    void markFirstChunk() {
        firstChunkAtNanos.compareAndSet(0L, System.nanoTime());
    }

    void recordUsage(TokenUsage tokens) {
        usage.set(tokens);
    }

    void addCompletionChars(int chars) {
        completionChars.addAndGet(chars);
    }

    public TokenUsage usage() {
        return usage.get();
    }

    public long completionChars() {
        return completionChars.get();
    }

    /** -1 when no upstream byte ever arrived. */
    public long firstByteMs() {
        long first = firstChunkAtNanos.get();
        return first == 0L ? -1L : (first - startedAtNanos) / 1_000_000L;
    }

    public long totalMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
