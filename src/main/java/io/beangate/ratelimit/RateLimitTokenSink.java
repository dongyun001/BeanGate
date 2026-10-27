package io.beangate.ratelimit;

/** Lets the async usage writer feed actual token counts back into the TPM window. */
public interface RateLimitTokenSink {

    void addTokens(Long apiKeyId, long tokens);
}
