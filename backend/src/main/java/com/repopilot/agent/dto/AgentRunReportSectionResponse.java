package com.repopilot.agent.dto;

import java.time.Instant;
import java.util.List;

import com.repopilot.agent.domain.AgentStepStatus;

public record AgentRunReportSectionResponse(
        String key,
        String title,
        String stepName,
        AgentStepStatus status,
        Instant finishedAt,
        String summary,
        List<String> facts,
        List<String> highlights
) {
}
