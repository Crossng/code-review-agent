package com.repopilot.settings.controller;

import com.repopilot.common.ApiResponse;
import com.repopilot.settings.dto.EmbeddingSettingsResponse;
import com.repopilot.settings.service.EmbeddingSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/embedding")
public class EmbeddingSettingsController {

    private final EmbeddingSettingsService embeddingSettingsService;

    public EmbeddingSettingsController(EmbeddingSettingsService embeddingSettingsService) {
        this.embeddingSettingsService = embeddingSettingsService;
    }

    @GetMapping
    public ApiResponse<EmbeddingSettingsResponse> get() {
        return ApiResponse.ok(embeddingSettingsService.current());
    }
}
