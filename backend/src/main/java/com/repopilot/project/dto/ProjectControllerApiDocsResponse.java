package com.repopilot.project.dto;

import java.time.Instant;

public record ProjectControllerApiDocsResponse(
        Long projectId,
        String repoFullName,
        Instant generatedAt,
        int routeCount,
        long filteredCount,
        ProjectControllerApiFiltersResponse filters,
        String markdown
) {
}
