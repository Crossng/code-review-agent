package com.repopilot.dashboard.dto;

public record DashboardSummaryResponse(
        long totalProjects,
        long readyProjects,
        long failedProjects,
        long totalTasks,
        long createdTasks,
        long runningTasks,
        long waitingApprovalTasks,
        long doneTasks,
        long failedTasks,
        long cancelledTasks,
        long totalPullRequests,
        long draftPullRequests,
        long openPullRequests,
        long failedPullRequests
) {
}
