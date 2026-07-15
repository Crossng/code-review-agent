package com.repopilot.project.dto;

import java.util.Map;

public record ProjectControllerApiRiskSummaryResponse(
        long total,
        Map<String, Long> byLevel
) {
}
