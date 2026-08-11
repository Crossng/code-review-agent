package com.repopilot.toolserver.tool;

import java.util.List;
import java.util.Map;

public record ToolValidationResponse(
        String toolName,
        boolean valid,
        String status,
        List<String> messages,
        Map<String, Object> normalizedArguments,
        boolean approvalRequired,
        boolean auditRequired
) {
}
