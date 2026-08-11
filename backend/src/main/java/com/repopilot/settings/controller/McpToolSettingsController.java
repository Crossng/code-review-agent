package com.repopilot.settings.controller;

import com.repopilot.common.ApiResponse;
import com.repopilot.settings.dto.McpToolSettingsResponse;
import com.repopilot.settings.service.McpToolSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/mcp-tools")
public class McpToolSettingsController {

    private final McpToolSettingsService mcpToolSettingsService;

    public McpToolSettingsController(McpToolSettingsService mcpToolSettingsService) {
        this.mcpToolSettingsService = mcpToolSettingsService;
    }

    @GetMapping
    public ApiResponse<McpToolSettingsResponse> get() {
        return ApiResponse.ok(mcpToolSettingsService.current());
    }
}
