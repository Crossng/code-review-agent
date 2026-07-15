package com.repopilot.modelcall.dto;

import java.time.Instant;

import com.repopilot.modelcall.domain.ModelCallLog;
import com.repopilot.modelcall.domain.ModelCallStatus;

public record ModelCallLogResponse(
        Long id,
        Long agentRunId,
        String stepName,
        String modelProvider,
        String modelName,
        String promptJson,
        String responseJson,
        ModelCallStatus status,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer durationMs,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {

    public static ModelCallLogResponse from(ModelCallLog log) {
        return new ModelCallLogResponse(
                log.getId(),
                log.getAgentRun().getId(),
                log.getStepName(),
                log.getModelProvider(),
                log.getModelName(),
                log.getPromptJson(),
                log.getResponseJson(),
                log.getStatus(),
                log.getPromptTokens(),
                log.getCompletionTokens(),
                log.getTotalTokens(),
                log.getDurationMs(),
                log.getErrorMessage(),
                log.getStartedAt(),
                log.getFinishedAt()
        );
    }
}
