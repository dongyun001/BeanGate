package io.beangate.admin;

import io.beangate.admin.AdminDtos.CreateRouteRequest;
import io.beangate.admin.AdminDtos.RouteView;
import io.beangate.route.RouteService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/routes")
public class AdminRouteController {

    private final RouteService routeService;

    public AdminRouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping
    public RouteView create(@Valid @RequestBody CreateRouteRequest request) {
        return RouteView.from(routeService.create(request.publicModel(), request.providerId(),
                request.upstreamModel(), request.priority(), request.enabled()));
    }

    @GetMapping
    public List<RouteView> list() {
        return routeService.findAll().stream().map(RouteView::from).toList();
    }
}
