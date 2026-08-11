package com.repopilot.pullrequest.domain;

public enum PullRequestPublishOutcome {
    LOCAL_DRAFT_READY,
    REMOTE_CREATED,
    REMOTE_REUSED_EXISTING,
    REMOTE_RECONCILED,
    REMOTE_FAILED
}
