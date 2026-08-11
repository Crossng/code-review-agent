package com.repopilot.toolserver.tool;

import java.util.List;

public record McpToolDefinition(
        String name,
        String title,
        String category,
        String description,
        boolean mvp,
        McpToolAccessMode accessMode,
        boolean auditRequired,
        boolean approvalRequired,
        String backendBridge,
        List<McpToolArgumentDefinition> arguments,
        List<String> safetyRules
) {
}
