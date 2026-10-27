package io.beangate.provider;

import io.beangate.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProviderStreamClients {

    private final List<ProviderStreamClient> clients;

    public ProviderStreamClients(List<ProviderStreamClient> clients) {
        this.clients = clients;
    }

    public ProviderStreamClient forProtocol(Protocol protocol) {
        return clients.stream()
                .filter(client -> client.supports(protocol))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_IMPLEMENTED,
                        "streaming is not supported for protocol " + protocol + " yet",
                        "server_error", "protocol_not_supported", null, null));
    }
}
