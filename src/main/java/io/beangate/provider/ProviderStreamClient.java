package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** Opens a streaming upstream call. Implementations must fail before any byte is handed to the caller. */
public interface ProviderStreamClient {

    boolean supports(Protocol protocol);

    StreamHandle open(Provider provider, JsonNode requestBody, String upstreamModel, String requestId,
                      String publicModel, boolean clientRequestedUsage);
}
