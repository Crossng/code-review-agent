package com.repopilot.settings.dto;

public record McpToolSettingsCheckResponse(
        String code,
        String label,
        String status,
        String message
) {
}
