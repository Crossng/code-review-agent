package com.repopilot.project.dto;

import java.util.List;

public record ProjectControllerApiListResponse(
        List<ProjectControllerApiResponse> items,
        long filteredCount,
        ProjectControllerApiRiskSummaryResponse riskSummary,
        List<String> riskCodes,
        ProjectControllerApiFiltersResponse filters
) {
}
