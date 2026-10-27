package io.beangate.route;

import io.beangate.provider.Provider;
import io.beangate.provider.ProviderRepository;
import io.beangate.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouteService {

    private final ModelRouteRepository routes;
    private final ProviderRepository providers;
    private final Router router;

    public RouteService(ModelRouteRepository routes, ProviderRepository providers, Router router) {
        this.routes = routes;
        this.providers = providers;
        this.router = router;
    }

    @Transactional
    public ModelRoute create(String publicModel, Long providerId, String upstreamModel, Integer priority,
                             Boolean enabled) {
        Provider provider = providers.findById(providerId)
                .orElseThrow(() -> ApiException.notFound("provider not found: " + providerId, "provider_not_found"));
        if (routes.existsByPublicModelAndProviderId(publicModel, providerId)) {
            throw ApiException.badRequest(
                    "a route for model '" + publicModel + "' on provider " + providerId + " already exists",
                    "duplicate_route", "publicModel");
        }
        ModelRoute route = new ModelRoute();
        route.setPublicModel(publicModel);
        route.setProvider(provider);
        route.setUpstreamModel(upstreamModel);
        route.setPriority(priority == null ? 100 : priority);
        route.setEnabled(enabled == null || enabled);
        ModelRoute saved = routes.save(route);
        router.invalidateAll();
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ModelRoute> findAll() {
        return routes.findAll();
    }
}
