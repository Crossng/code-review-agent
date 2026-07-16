package com.repopilot.agent.worker;

import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTaskStatus;

public record AgentWorkerPatchApprovalReadyResponse(
        Long patchId,
        Long agentTaskId,
        Long agentRunId,
        Long testRunId,
        Long reviewStepId,
        Long approvalStepId,
        AgentStepStatus approvalStepStatus,
        AgentTaskStatus taskStatus,
        AgentRunStatus runStatus,
        boolean streamCompleted
) {
    static AgentWorkerPatchApprovalReadyResponse from(
            Long patchId,
            Long agentTaskId,
            Long agentRunId,
            Long testRunId,
            Long reviewStepId,
            Long approvalStepId,
            AgentStepStatus approvalStepStatus,
            AgentTaskStatus taskStatus,
            AgentRunStatus runStatus,
            boolean streamCompleted
    ) {
        return new AgentWorkerPatchApprovalReadyResponse(
                patchId,
                agentTaskId,
                agentRunId,
                testRunId,
                reviewStepId,
                approvalStepId,
                approvalStepStatus,
                taskStatus,
                runStatus,
                streamCompleted
        );
    }
}
