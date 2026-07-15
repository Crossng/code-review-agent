package com.repopilot.settings.dto;

public record SandboxSettingsCheckResponse(
        String code,
        String label,
        String status,
        String message
) {
}
