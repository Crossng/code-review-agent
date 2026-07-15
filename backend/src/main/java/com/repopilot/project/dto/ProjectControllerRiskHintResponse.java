package com.repopilot.project.dto;

import java.util.List;

public record ProjectControllerRiskHintResponse(
        String severity,
        String code,
        String message,
        List<String> details
) {
    public ProjectControllerRiskHintResponse(String severity, String code, String message) {
        this(severity, code, message, List.of());
    }

    public ProjectControllerRiskHintResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
