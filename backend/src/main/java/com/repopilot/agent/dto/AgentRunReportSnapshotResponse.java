package com.repopilot.agent.dto;

import java.time.Instant;

import com.repopilot.agent.domain.AgentRunReportSnapshot;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;

public record AgentRunReportSnapshotResponse(
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
        String markdown,
        Instant createdAt
) {

    public static AgentRunReportSnapshotResponse from(AgentRunReportSnapshot snapshot) {
        return new AgentRunReportSnapshotResponse(
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
                snapshot.getMarkdown(),
                snapshot.getCreatedAt()
        );
    }
}
