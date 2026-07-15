package com.repopilot.repository.dto;

import com.repopilot.project.domain.ProjectStatus;

public record CloneProjectResponse(
        Long projectId,
        ProjectStatus status,
        String localPath,
        String branch,
        String commitSha,
        int fileCount,
        int javaFileCount,
        String message
) {
}

