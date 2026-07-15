package com.repopilot.sandbox.controller;

import java.util.List;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.sandbox.dto.TestRunResponse;
import com.repopilot.sandbox.service.TestRunQueryService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agent/runs/{runId}/test-runs")
public class TestRunController {

    private final TestRunQueryService testRunQueryService;

    public TestRunController(TestRunQueryService testRunQueryService) {
        this.testRunQueryService = testRunQueryService;
    }

    @GetMapping
    public ApiResponse<List<TestRunResponse>> list(
            @PathVariable @Positive Long runId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(testRunQueryService.listByRun(runId, principal.getId())
                .stream()
                .map(TestRunResponse::from)
                .toList());
    }
}
