package com.repopilot.agent.dto;

import java.time.Instant;

import com.repopilot.agent.domain.AgentRunReportSnapshot;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;

public record AgentRunReportSnapshotSummaryResponse(
        Long id,
        Long taskId,
        Long runId,
        Long projectId,
        Long generatedByUserId,
        String projectName,
        String taskTitle,
        AgentTaskType taskType,
        AgentTaskStatus taskStatus,
        AgentRunStatus runStatus,
        Instant runStartedAt,
        Instant runFinishedAt,
        Instant reportGeneratedAt,
        int sectionCount,
        Instant createdAt
) {

    public static AgentRunReportSnapshotSummaryResponse from(AgentRunReportSnapshot snapshot) {
        return new AgentRunReportSnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getAgentTask().getId(),
                snapshot.getAgentRun() == null ? null : snapshot.getAgentRun().getId(),
                snapshot.getProject().getId(),
                snapshot.getGeneratedBy().getId(),
                snapshot.getProjectName(),
                snapshot.getTaskTitle(),
                snapshot.getTaskType(),
                snapshot.getTaskStatus(),
                snapshot.getRunStatus(),
                snapshot.getRunStartedAt(),
                snapshot.getRunFinishedAt(),
                snapshot.getReportGeneratedAt(),
                snapshot.getSectionCount(),
                snapshot.getCreatedAt()
        );
    }
}
