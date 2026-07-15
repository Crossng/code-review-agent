package com.repopilot.dashboard.dto;

public record DashboardRunTrendPointResponse(
        String date,
        long totalRuns,
        long successRuns,
        long failedRuns,
        long cancelledRuns,
        long runningRuns,
        long averageDurationSeconds
) {
}
