package dev.novel.controller;

import dev.novel.dto.response.SystemResponses.ProviderCatalogueResponse;
import dev.novel.integration.model.ModelCatalog;
import dev.novel.support.ResponseMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {
    private final ModelCatalog catalog;
    private final ResponseMapper responses;

    public ProviderController(ModelCatalog catalog, ResponseMapper responses) {
        this.catalog = catalog;
        this.responses = responses;
    }

    @GetMapping
    public ProviderCatalogueResponse catalogue() {
        return responses.to(catalog.publicProfiles(), ProviderCatalogueResponse.class);
    }

    @GetMapping("/{id}/models")
    public List<String> models(@PathVariable String id) {
        return catalog.models(id);
    }
}
