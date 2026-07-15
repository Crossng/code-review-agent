package com.repopilot.dashboard.dto;

import java.time.Instant;

public record DashboardActivityItemResponse(
        Long stepId,
        Long runId,
        Long taskId,
        Long projectId,
        String projectName,
        String taskTitle,
        String taskStatus,
        String activityType,
        String label,
        String status,
        String message,
        Instant occurredAt
) {
}
