package com.repopilot.project.dto;

import java.time.Instant;

import com.repopilot.project.domain.ProjectControllerApiDocSnapshot;

public record ProjectControllerApiDocsSnapshotResponse(
        Long id,
        Long projectId,
        Long generatedByUserId,
        String repoFullName,
        Instant generatedAt,
        int routeCount,
        long filteredCount,
        ProjectControllerApiFiltersResponse filters,
        String markdown,
        Instant createdAt
) {

    public static ProjectControllerApiDocsSnapshotResponse from(ProjectControllerApiDocSnapshot snapshot) {
        return new ProjectControllerApiDocsSnapshotResponse(
                snapshot.getId(),
                snapshot.getProject().getId(),
                snapshot.getGeneratedBy().getId(),
                snapshot.getRepoFullName(),
                snapshot.getGeneratedAt(),
                snapshot.getRouteCount(),
                snapshot.getFilteredCount(),
                new ProjectControllerApiFiltersResponse(snapshot.getRiskLevel(), snapshot.getRiskCode()),
                snapshot.getMarkdown(),
                snapshot.getCreatedAt()
        );
    }
}
