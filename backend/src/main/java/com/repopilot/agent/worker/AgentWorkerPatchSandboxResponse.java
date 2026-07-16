package com.repopilot.agent.worker;

import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.service.SandboxTestService;

public record AgentWorkerPatchSandboxResponse(
        Long patchId,
        Long agentTaskId,
        Long agentRunId,
        PatchStatus patchStatus,
        boolean applied,
        boolean testsPassed,
        Long applyStepId,
        AgentStepStatus applyStepStatus,
        SandboxCommandOutput applyResult,
        Long testStepId,
        AgentStepStatus testStepStatus,
        Long testRunId,
        TestRunStatus testStatus,
        TestRunOutput testRun
) {
    static AgentWorkerPatchSandboxResponse applyFailed(
            Long patchId,
            Long agentTaskId,
            Long agentRunId,
            PatchStatus patchStatus,
            Long applyStepId,
            AgentStepStatus applyStepStatus,
            SandboxTestService.CommandResult applyResult
    ) {
        return new AgentWorkerPatchSandboxResponse(
                patchId,
                agentTaskId,
                agentRunId,
                patchStatus,
                false,
                false,
                applyStepId,
                applyStepStatus,
                SandboxCommandOutput.from(applyResult),
                null,
                null,
                null,
                null,
                null
        );
    }

    static AgentWorkerPatchSandboxResponse from(
            Long patchId,
            Long agentTaskId,
            Long agentRunId,
            PatchStatus patchStatus,
            Long applyStepId,
            AgentStepStatus applyStepStatus,
            SandboxTestService.CommandResult applyResult,
            Long testStepId,
            AgentStepStatus testStepStatus,
            TestRun testRun
    ) {
        return new AgentWorkerPatchSandboxResponse(
                patchId,
                agentTaskId,
                agentRunId,
                patchStatus,
                true,
                testRun.getStatus() == TestRunStatus.PASSED,
                applyStepId,
                applyStepStatus,
                SandboxCommandOutput.from(applyResult),
                testStepId,
                testStepStatus,
                testRun.getId(),
                testRun.getStatus(),
                TestRunOutput.from(testRun)
        );
    }

    public record SandboxWorkspaceOutput(String runRoot, String sourcePath, String patchPath) {
        static SandboxWorkspaceOutput from(SandboxTestService.SandboxWorkspace workspace) {
            return new SandboxWorkspaceOutput(
                    workspace.runRoot().toString(),
                    workspace.sourcePath().toString(),
                    workspace.patchPath().toString()
            );
        }
    }

    public record SandboxCommandOutput(
            String command,
            Integer exitCode,
            boolean timedOut,
            Integer durationMs,
            String logExcerpt,
            String logPath
    ) {
        static SandboxCommandOutput from(SandboxTestService.CommandResult result) {
            return new SandboxCommandOutput(
                    result.command(),
                    result.exitCode(),
                    result.timedOut(),
                    result.durationMs(),
                    result.logExcerpt(),
                    result.logPath()
            );
        }
    }

    public record TestRunOutput(
            Long testRunId,
            Long patchId,
            TestRunStatus status,
            String command,
            Integer exitCode,
            Integer durationMs,
            String logExcerpt
    ) {
        static TestRunOutput from(TestRun testRun) {
            return new TestRunOutput(
                    testRun.getId(),
                    testRun.getPatch().getId(),
                    testRun.getStatus(),
                    testRun.getCommand(),
                    testRun.getExitCode(),
                    testRun.getDurationMs(),
                    testRun.getLogExcerpt()
            );
        }
    }
}
