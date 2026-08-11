package com.repopilot.settings.dto;

import java.util.List;

public record McpToolSettingsResponse(
        String provider,
        boolean ready,
        String baseUrl,
        boolean healthCheckEnabled,
        boolean healthAvailable,
        String healthStatus,
        String serviceName,
        String protocolVersion,
        int toolCount,
        int mvpToolCount,
        int readToolCount,
        int writeToolCount,
        int auditRequiredToolCount,
        int approvalRequiredToolCount,
        boolean requiredToolsPresent,
        List<String> requiredTools,
        List<String> missingRequiredTools,
        List<String> categories,
        List<McpToolSummaryResponse> tools,
        List<String> missingRequirements,
        List<McpToolSettingsCheckResponse> checks
) {
}
