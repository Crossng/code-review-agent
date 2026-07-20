package com.repopilot.toolcall.dto;

import java.time.Instant;

import com.repopilot.common.RetryAuditSummaryResponse;
import com.repopilot.toolcall.domain.ToolCallLog;
import com.repopilot.toolcall.domain.ToolCallStatus;

public record ToolCallLogResponse(
        Long id,
        Long agentRunId,
        String toolName,
        String inputJson,
        String outputJson,
        RetryAuditSummaryResponse retryAudit,
        ToolCallStatus status,
        Integer durationMs,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {

    public static ToolCallLogResponse from(ToolCallLog log) {
        return new ToolCallLogResponse(
                log.getId(),
                log.getAgentRun().getId(),
                log.getToolName(),
                log.getInputJson(),
                log.getOutputJson(),
                RetryAuditSummaryResponse.fromJson(log.getOutputJson(), log.getStatus() == ToolCallStatus.SUCCESS),
                log.getStatus(),
                log.getDurationMs(),
                log.getErrorMessage(),
                log.getStartedAt(),
                log.getFinishedAt()
        );
    }
}
