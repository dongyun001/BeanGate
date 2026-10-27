package io.beangate.provider;

import io.beangate.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Picks the adapter for a protocol. Adding Anthropic (W3) means adding one bean. */
@Service
public class ProviderClients {

    private final List<ProviderClient> clients;

    public ProviderClients(List<ProviderClient> clients) {
        this.clients = clients;
    }

    public ProviderClient forProtocol(Protocol protocol) {
        return clients.stream()
                .filter(client -> client.supports(protocol))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.NOT_IMPLEMENTED,
                        "protocol " + protocol + " is not supported yet",
                        "server_error", "protocol_not_supported", null, null));
    }
}
