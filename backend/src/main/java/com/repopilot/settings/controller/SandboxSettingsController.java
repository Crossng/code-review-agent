package com.repopilot.settings.controller;

import com.repopilot.common.ApiResponse;
import com.repopilot.settings.dto.SandboxSettingsResponse;
import com.repopilot.settings.service.SandboxSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/sandbox")
public class SandboxSettingsController {

    private final SandboxSettingsService sandboxSettingsService;

    public SandboxSettingsController(SandboxSettingsService sandboxSettingsService) {
        this.sandboxSettingsService = sandboxSettingsService;
    }

    @GetMapping
    public ApiResponse<SandboxSettingsResponse> get() {
        return ApiResponse.ok(sandboxSettingsService.current());
    }
}
