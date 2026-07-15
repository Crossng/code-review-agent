package com.repopilot.agent.dto;

import java.time.Instant;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;

public record AgentRunResponse(
        Long id,
        Long taskId,
        AgentRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage
) {

    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(
                run.getId(),
                run.getAgentTask().getId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getErrorMessage()
        );
    }
}

