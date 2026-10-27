package io.beangate.usage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Records Micrometer metrics and hands the record to the async writer. Tags are limited to bounded
 * dimensions (model, provider, status, stream) — never request ids or key ids, which would blow up
 * Prometheus cardinality.
 */
@Component
@Primary
public class MeteredUsageRecorder implements UsageRecorder {

    private final AsyncUsageRecorder delegate;
    private final MeterRegistry registry;

    public MeteredUsageRecorder(AsyncUsageRecorder delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public void record(UsageRecord record) {
        String provider = orUnknown(record.providerName());
        String model = orUnknown(record.publicModel());
        String stream = Boolean.toString(record.stream());

        registry.counter("beangate.requests", "model", model, "provider", provider,
                "stream", stream, "status", Integer.toString(record.httpStatus())).increment();

        Timer.builder("beangate.request.latency")
                .description("End-to-end gateway latency")
                .tag("stream", stream)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(record.latencyMs()));

        if (record.firstTokenMs() != null && record.firstTokenMs() >= 0) {
            Timer.builder("beangate.ttft")
                    .description("Time to first upstream byte")
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofMillis(record.firstTokenMs()));
        }
        if (record.promptTokens() != null) {
            registry.counter("beangate.tokens", "type", "prompt").increment(record.promptTokens());
        }
        if (record.completionTokens() != null) {
            registry.counter("beangate.tokens", "type", "completion").increment(record.completionTokens());
        }
        if (record.errorCode() != null) {
            registry.counter("beangate.errors", "code", record.errorCode(), "provider", provider).increment();
        }

        delegate.record(record);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
