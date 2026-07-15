package com.repopilot.pullrequest.dto;

import java.time.Instant;

import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.pullrequest.domain.PullRequestProvider;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;

public record PullRequestRecordResponse(
        Long id,
        Long agentTaskId,
        Long patchId,
        PullRequestProvider provider,
        Integer prNumber,
        String url,
        String title,
        String body,
        String baseBranch,
        String targetBranch,
        String commitSha,
        String commitMessage,
        PullRequestStatus status,
        Instant remotePushedAt,
        Instant openedAt,
        String errorMessage,
        AgentTaskStatus taskStatus,
        Instant createdAt,
        Instant updatedAt
) {

    public static PullRequestRecordResponse from(PullRequestRecord record) {
        return new PullRequestRecordResponse(
                record.getId(),
                record.getAgentTask().getId(),
                record.getPatch().getId(),
                record.getProvider(),
                record.getPrNumber(),
                record.getUrl(),
                record.getTitle(),
                record.getBody(),
                record.getBaseBranch(),
                record.getTargetBranch(),
                record.getCommitSha(),
                record.getCommitMessage(),
                record.getStatus(),
                record.getRemotePushedAt(),
                record.getOpenedAt(),
                record.getErrorMessage(),
                record.getAgentTask().getStatus(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
