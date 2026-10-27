package io.beangate.admin;

import io.beangate.admin.AdminDtos.ApiKeyView;
import io.beangate.admin.AdminDtos.CreateKeyRequest;
import io.beangate.admin.AdminDtos.CreatedKeyResponse;
import io.beangate.admin.AdminDtos.UpdateKeyStatusRequest;
import io.beangate.key.ApiKeyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Requires {@code X-Admin-Token} (see {@link io.beangate.web.AdminAuthFilter}). */
@RestController
@RequestMapping("/admin/keys")
public class AdminKeyController {

    private final ApiKeyService apiKeyService;

    public AdminKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /** The plaintext key appears in this response only; it is never retrievable again. */
    @PostMapping
    public CreatedKeyResponse create(@Valid @RequestBody CreateKeyRequest request) {
        return CreatedKeyResponse.from(apiKeyService.create(request.name(), request.allowedModels(),
                request.rpmLimit(), request.tpmLimit(), request.dailyTokenLimit()));
    }

    @GetMapping
    public List<ApiKeyView> list() {
        return apiKeyService.findAll().stream().map(ApiKeyView::from).toList();
    }

    @PatchMapping("/{id}/status")
    public ApiKeyView updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateKeyStatusRequest request) {
        return ApiKeyView.from(apiKeyService.setStatus(id, request.status()));
    }
}
