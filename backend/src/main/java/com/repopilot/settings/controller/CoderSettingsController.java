package com.repopilot.settings.controller;

import com.repopilot.common.ApiResponse;
import com.repopilot.settings.dto.CoderSettingsResponse;
import com.repopilot.settings.service.CoderSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/coder")
public class CoderSettingsController {

    private final CoderSettingsService coderSettingsService;

    public CoderSettingsController(CoderSettingsService coderSettingsService) {
        this.coderSettingsService = coderSettingsService;
    }

    @GetMapping
    public ApiResponse<CoderSettingsResponse> get() {
        return ApiResponse.ok(coderSettingsService.current());
    }
}
