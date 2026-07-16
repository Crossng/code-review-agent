package com.repopilot.agent.worker;

import java.util.List;

import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.service.PatchDiffSafetyService;

public record AgentWorkerPatchSafetyResponse(
        Long patchId,
        Long agentTaskId,
        Long agentRunId,
        boolean safe,
        List<String> changedPaths,
        List<PatchDiffSafetyService.PatchDiffSafetyFinding> findings,
        Long stepId,
        AgentStepStatus stepStatus
) {
    static AgentWorkerPatchSafetyResponse from(
            Long patchId,
            Long agentTaskId,
            Long agentRunId,
            PatchDiffSafetyService.PatchDiffSafetyReport report,
            Long stepId,
            AgentStepStatus stepStatus
    ) {
        return new AgentWorkerPatchSafetyResponse(
                patchId,
                agentTaskId,
                agentRunId,
                report.safe(),
                report.changedPaths(),
                report.findings(),
                stepId,
                stepStatus
        );
    }
}
