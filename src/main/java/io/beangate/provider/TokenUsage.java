package io.beangate.provider;

/** Token counts reported by the upstream, when it reports them at all. */
public record TokenUsage(Integer prompt, Integer completion, Integer total) {
}
