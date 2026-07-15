package com.repopilot.project.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
        @NotBlank String repoUrl,
        String accessToken,
        String defaultBranch
) {
}

