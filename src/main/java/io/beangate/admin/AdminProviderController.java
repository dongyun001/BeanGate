package io.beangate.admin;

import io.beangate.admin.AdminDtos.CreateProviderRequest;
import io.beangate.admin.AdminDtos.ProviderView;
import io.beangate.admin.AdminDtos.UpdateEnabledRequest;
import io.beangate.provider.ProviderService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/providers")
public class AdminProviderController {

    private final ProviderService providerService;

    public AdminProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping
    public ProviderView create(@Valid @RequestBody CreateProviderRequest request) {
        return ProviderView.from(providerService.create(request.name(), request.protocol(), request.baseUrl(),
                request.apiKey(), request.priority(), request.enabled(), request.supportsStreamUsage()));
    }

    @GetMapping
    public List<ProviderView> list() {
        return providerService.findAll().stream().map(ProviderView::from).toList();
    }

    @PatchMapping("/{id}/enabled")
    public ProviderView setEnabled(@PathVariable Long id, @RequestBody UpdateEnabledRequest request) {
        return ProviderView.from(providerService.setEnabled(id, request.enabled()));
    }
}
