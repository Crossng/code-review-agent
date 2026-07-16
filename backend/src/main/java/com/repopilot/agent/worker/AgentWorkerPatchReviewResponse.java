package com.repopilot.agent.worker;

import java.util.List;

import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.service.PatchRiskReviewService;

public record AgentWorkerPatchReviewResponse(
        Long patchId,
        Long agentTaskId,
        Long agentRunId,
        Long testRunId,
        String riskLevel,
        String summary,
        List<PatchRiskReviewService.PatchRiskFinding> findings,
        Long stepId,
        AgentStepStatus stepStatus
) {
    static AgentWorkerPatchReviewResponse from(
            Long patchId,
            Long agentTaskId,
            Long agentRunId,
            Long testRunId,
            PatchRiskReviewService.PatchRiskReview review,
            Long stepId,
            AgentStepStatus stepStatus
    ) {
        return new AgentWorkerPatchReviewResponse(
                patchId,
                agentTaskId,
                agentRunId,
                testRunId,
                review.riskLevel(),
                review.summary(),
                review.findings(),
                stepId,
                stepStatus
        );
    }
}
