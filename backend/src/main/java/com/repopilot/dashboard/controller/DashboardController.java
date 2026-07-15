package com.repopilot.dashboard.controller;

import java.util.List;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.dashboard.dto.DashboardActivityItemResponse;
import com.repopilot.dashboard.dto.DashboardRunMetricsResponse;
import com.repopilot.dashboard.dto.DashboardSummaryResponse;
import com.repopilot.dashboard.service.DashboardSummaryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardSummaryService dashboardSummaryService;

    public DashboardController(DashboardSummaryService dashboardSummaryService) {
        this.dashboardSummaryService = dashboardSummaryService;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryResponse> summary(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(dashboardSummaryService.current(principal.getId()));
    }

    @GetMapping("/run-metrics")
    public ApiResponse<DashboardRunMetricsResponse> runMetrics(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "7") int days
    ) {
        return ApiResponse.ok(dashboardSummaryService.runMetrics(principal.getId(), days));
    }

    @GetMapping("/activity")
    public ApiResponse<List<DashboardActivityItemResponse>> activity(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(dashboardSummaryService.activity(principal.getId(), limit));
    }
}
