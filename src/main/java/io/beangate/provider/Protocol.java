package io.beangate.provider;

public enum Protocol {
    /** OpenAI-compatible /chat/completions, including DeepSeek, OpenAI and Ollama's compat endpoint. */
    OPENAI_COMPAT,
    /** Ollama's OpenAI-compatible endpoint. Kept distinct so behaviour can diverge later. */
    OLLAMA,
    /** Anthropic /v1/messages. Non-streaming text-only in the MVP. */
    ANTHROPIC
}
