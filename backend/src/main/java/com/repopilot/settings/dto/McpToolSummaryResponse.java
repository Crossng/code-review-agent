package com.repopilot.settings.dto;

public record McpToolSummaryResponse(
        String name,
        String title,
        String category,
        String accessMode,
        boolean mvp,
        boolean auditRequired,
        boolean approvalRequired
) {
}
