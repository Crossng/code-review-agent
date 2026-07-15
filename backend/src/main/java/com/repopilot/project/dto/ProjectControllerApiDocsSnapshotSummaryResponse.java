package com.repopilot.project.dto;

import java.time.Instant;

import com.repopilot.project.domain.ProjectControllerApiDocSnapshot;

public record ProjectControllerApiDocsSnapshotSummaryResponse(
        Long id,
        Long projectId,
        Long generatedByUserId,
        String repoFullName,
        Instant generatedAt,
        int routeCount,
        long filteredCount,
        ProjectControllerApiFiltersResponse filters,
        Instant createdAt
) {

    public static ProjectControllerApiDocsSnapshotSummaryResponse from(ProjectControllerApiDocSnapshot snapshot) {
        return new ProjectControllerApiDocsSnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getProject().getId(),
                snapshot.getGeneratedBy().getId(),
                snapshot.getRepoFullName(),
                snapshot.getGeneratedAt(),
                snapshot.getRouteCount(),
                snapshot.getFilteredCount(),
                new ProjectControllerApiFiltersResponse(snapshot.getRiskLevel(), snapshot.getRiskCode()),
                snapshot.getCreatedAt()
        );
    }
}
