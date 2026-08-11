package com.repopilot.settings.dto;

import java.util.List;

public record McpToolSummaryResponse(
        String name,
        String title,
        String category,
        String description,
        String accessMode,
        boolean mvp,
        boolean auditRequired,
        boolean approvalRequired,
        String backendBridge,
        List<McpToolArgumentResponse> arguments,
        List<String> safetyRules
) {
}
