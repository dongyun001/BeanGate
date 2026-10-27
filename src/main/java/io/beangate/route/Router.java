package io.beangate.route;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Public model → ordered provider routes.
 *
 * <p>Resolved on every request, so results are cached. Admin changes invalidate the cache explicitly;
 * the TTL only bounds staleness for writes made by another instance.
 */
@Service
public class Router {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final ModelRouteRepository routes;
    private final Cache<String, List<ResolvedRoute>> byPublicModel = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public Router(ModelRouteRepository routes) {
        this.routes = routes;
    }

    /**
     * Ordered candidate routes for a public model; the first entry is the primary.
     *
     * <p>Not {@code @Transactional} on purpose: wrapping this in a transaction would take a JDBC
     * connection on every request, including cache hits. On a miss the repository call opens its own.
     */
    public List<ResolvedRoute> resolve(String publicModel) {
        return byPublicModel.get(publicModel, this::load);
    }

    public List<String> enabledPublicModels() {
        return routes.findEnabledPublicModels();
    }

    public void invalidateAll() {
        byPublicModel.invalidateAll();
    }

    private List<ResolvedRoute> load(String publicModel) {
        return routes.findByPublicModelAndEnabledTrueOrderByPriorityAsc(publicModel).stream()
                .filter(route -> route.getProvider().isEnabled())
                .map(route -> new ResolvedRoute(route.getProvider(), route.getUpstreamModel()))
                .toList();
    }
}
