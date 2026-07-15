package com.repopilot.project.dto;

import java.time.Instant;

import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;

public record ProjectResponse(
        Long id,
        String repoUrl,
        String repoFullName,
        String defaultBranch,
        String localPath,
        ProjectStatus status,
        Instant lastIndexedAt,
        Instant createdAt
) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getRepoUrl(),
                project.getRepoFullName(),
                project.getDefaultBranch(),
                project.getLocalPath(),
                project.getStatus(),
                project.getLastIndexedAt(),
                project.getCreatedAt()
        );
    }
}

