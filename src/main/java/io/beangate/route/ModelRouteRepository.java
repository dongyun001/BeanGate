package io.beangate.route;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ModelRouteRepository extends JpaRepository<ModelRoute, Long> {

    List<ModelRoute> findByPublicModelAndEnabledTrueOrderByPriorityAsc(String publicModel);

    boolean existsByPublicModelAndProviderId(String publicModel, Long providerId);

    @Query("""
            select distinct r.publicModel
            from ModelRoute r
            where r.enabled = true and r.provider.enabled = true
            order by r.publicModel
            """)
    List<String> findEnabledPublicModels();
}
