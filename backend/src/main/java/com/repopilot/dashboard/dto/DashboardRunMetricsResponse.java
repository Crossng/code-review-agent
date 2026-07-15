package com.repopilot.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record DashboardRunMetricsResponse(
        int days,
        Instant from,
        Instant to,
        long totalRuns,
        long successRuns,
        long failedRuns,
        long cancelledRuns,
        long runningRuns,
        long completedRuns,
        long averageDurationSeconds,
        long successRatePercent,
        List<DashboardRunTrendPointResponse> trend
) {
}
