package com.repopilot.pullrequest.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.pullrequest.dto.PullRequestPreflightResponse;
import com.repopilot.pullrequest.dto.PullRequestRecordResponse;
import com.repopilot.pullrequest.service.PullRequestService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tasks/{taskId}/pull-request")
public class PullRequestController {

    private final PullRequestService pullRequestService;

    public PullRequestController(PullRequestService pullRequestService) {
        this.pullRequestService = pullRequestService;
    }

    @PostMapping
    public ApiResponse<PullRequestRecordResponse> prepare(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(PullRequestRecordResponse.from(
                pullRequestService.prepare(taskId, principal.getId())
        ));
    }

    @GetMapping
    public ApiResponse<PullRequestRecordResponse> latest(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(PullRequestRecordResponse.from(
                pullRequestService.latest(taskId, principal.getId())
        ));
    }

    @GetMapping("/preflight")
    public ApiResponse<PullRequestPreflightResponse> preflight(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(pullRequestService.preflight(taskId, principal.getId()));
    }
}
