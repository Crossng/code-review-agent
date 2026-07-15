package com.repopilot.agent.dto;

import java.time.Instant;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;

public record AgentTaskResponse(
        Long id,
        Long projectId,
        AgentTaskType taskType,
        String title,
        String description,
        AgentTaskStatus status,
        Long currentRunId,
        Instant createdAt
) {

    public static AgentTaskResponse from(AgentTask task) {
        Long runId = task.getCurrentRun() == null ? null : task.getCurrentRun().getId();
        return new AgentTaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getTaskType(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                runId,
                task.getCreatedAt()
        );
    }
}

