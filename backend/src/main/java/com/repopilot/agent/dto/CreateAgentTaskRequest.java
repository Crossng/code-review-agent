package com.repopilot.agent.dto;

import com.repopilot.agent.domain.AgentTaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAgentTaskRequest(
        @NotNull Long projectId,
        @NotNull AgentTaskType taskType,
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description
) {
}

