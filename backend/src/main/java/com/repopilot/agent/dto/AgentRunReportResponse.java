package com.repopilot.agent.dto;

import java.time.Instant;
import java.util.List;

import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;

public record AgentRunReportResponse(
        Long taskId,
        Long runId,
        Long projectId,
        String projectName,
        AgentTaskType taskType,
        String taskTitle,
        AgentTaskStatus taskStatus,
        AgentRunStatus runStatus,
        Instant startedAt,
        Instant finishedAt,
        Instant generatedAt,
        List<AgentRunReportSectionResponse> sections,
        String markdown
) {
}
