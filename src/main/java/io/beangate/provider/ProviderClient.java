package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderClient {

    boolean supports(Protocol protocol);

    /**
     * Executes one non-streaming chat completion.
     *
     * @param provider      upstream channel (credential already encrypted in storage)
     * @param requestBody   the caller's JSON body; implementations must not mutate it
     * @param upstreamModel model name rewritten for this specific provider
     * @param requestId     gateway request id, propagated upstream when the protocol allows it
     */
    ChatResult call(Provider provider, JsonNode requestBody, String upstreamModel, String requestId);
}
