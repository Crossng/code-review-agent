package com.repopilot.sandbox.dto;

import java.time.Instant;

import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;

public record TestRunResponse(
        Long id,
        Long agentRunId,
        Long patchId,
        String command,
        Integer exitCode,
        Integer durationMs,
        String logExcerpt,
        TestRunStatus status,
        Instant createdAt
) {

    public static TestRunResponse from(TestRun testRun) {
        return new TestRunResponse(
                testRun.getId(),
                testRun.getAgentRun().getId(),
                testRun.getPatch().getId(),
                testRun.getCommand(),
                testRun.getExitCode(),
                testRun.getDurationMs(),
                testRun.getLogExcerpt(),
                testRun.getStatus(),
                testRun.getCreatedAt()
        );
    }
}
