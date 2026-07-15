package com.repopilot.agent.dto;

import java.time.Instant;

import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;

public record AgentStepResponse(
        Long id,
        String stepName,
        AgentStepStatus status,
        String inputJson,
        String outputJson,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {

    public static AgentStepResponse from(AgentStep step) {
        return new AgentStepResponse(
                step.getId(),
                step.getStepName(),
                step.getStatus(),
                step.getInputJson(),
                step.getOutputJson(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt()
        );
    }
}

