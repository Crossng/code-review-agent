package com.repopilot.notification.dto;

import java.time.Instant;

public record AgentEventResponse(
        String eventType,
        Long taskId,
        String taskStatus,
        Long runId,
        String runStatus,
        Long stepId,
        String stepName,
        String stepStatus,
        String message,
        Instant createdAt
) {
}
