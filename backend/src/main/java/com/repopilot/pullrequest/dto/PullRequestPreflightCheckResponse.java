package com.repopilot.pullrequest.dto;

public record PullRequestPreflightCheckResponse(
        String code,
        String label,
        String status,
        String message
) {
}
