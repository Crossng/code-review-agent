package com.repopilot.pullrequest.dto;

import java.util.List;

import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.sandbox.domain.TestRunStatus;

public record PullRequestPreflightResponse(
        Long taskId,
        AgentTaskStatus taskStatus,
        boolean canPrepare,
        String publishMode,
        boolean localDraftReady,
        boolean remotePublishingEnabled,
        boolean remotePublishingWillRun,
        boolean remoteReady,
        boolean repositoryEligible,
        boolean tokenConfigured,
        PatchStatus latestPatchStatus,
        TestRunStatus latestTestStatus,
        PullRequestStatus existingPullRequestStatus,
        List<PullRequestPreflightCheckResponse> checks,
        List<String> blockers
) {
}
