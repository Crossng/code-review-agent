package com.repopilot.dashboard.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.dashboard.dto.DashboardActivityItemResponse;
import com.repopilot.dashboard.dto.DashboardRunMetricsResponse;
import com.repopilot.dashboard.dto.DashboardRunTrendPointResponse;
import com.repopilot.dashboard.dto.DashboardSummaryResponse;
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.pullrequest.repository.PullRequestRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardSummaryService {

    private static final Set<AgentTaskStatus> RUNNING_STATUSES = Set.of(
            AgentTaskStatus.REPO_INDEXING,
            AgentTaskStatus.PLANNING,
            AgentTaskStatus.RETRIEVING_CONTEXT,
            AgentTaskStatus.GENERATING_PATCH,
            AgentTaskStatus.APPLYING_PATCH_IN_SANDBOX,
            AgentTaskStatus.RUNNING_TESTS,
            AgentTaskStatus.REPAIRING,
            AgentTaskStatus.REVIEWING_PATCH,
            AgentTaskStatus.CREATING_PULL_REQUEST
    );
    private static final Set<AgentTaskStatus> FAILED_STATUSES = Set.of(
            AgentTaskStatus.FAILED_REPO_CLONE,
            AgentTaskStatus.FAILED_INDEXING,
            AgentTaskStatus.FAILED_CONTEXT_RETRIEVAL,
            AgentTaskStatus.FAILED_PATCH_GENERATION,
            AgentTaskStatus.FAILED_TEST,
            AgentTaskStatus.FAILED_PR_CREATION
    );

    private final ProjectRepository projectRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;
    private final PullRequestRecordRepository pullRequestRecordRepository;

    public DashboardSummaryService(
            ProjectRepository projectRepository,
            AgentTaskRepository agentTaskRepository,
            AgentRunRepository agentRunRepository,
            AgentStepRepository agentStepRepository,
            PullRequestRecordRepository pullRequestRecordRepository
    ) {
        this.projectRepository = projectRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentStepRepository = agentStepRepository;
        this.pullRequestRecordRepository = pullRequestRecordRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse current(Long userId) {
        return new DashboardSummaryResponse(
                projectRepository.countByOwnerId(userId),
                projectRepository.countByOwnerIdAndStatus(userId, ProjectStatus.READY),
                projectRepository.countByOwnerIdAndStatus(userId, ProjectStatus.FAILED),
                agentTaskRepository.countByUserId(userId),
                agentTaskRepository.countByUserIdAndStatus(userId, AgentTaskStatus.CREATED),
                agentTaskRepository.countByUserIdAndStatusIn(userId, RUNNING_STATUSES),
                agentTaskRepository.countByUserIdAndStatus(userId, AgentTaskStatus.WAITING_HUMAN_APPROVAL),
                agentTaskRepository.countByUserIdAndStatus(userId, AgentTaskStatus.DONE),
                agentTaskRepository.countByUserIdAndStatusIn(userId, FAILED_STATUSES),
                agentTaskRepository.countByUserIdAndStatus(userId, AgentTaskStatus.CANCELLED),
                pullRequestRecordRepository.countByUserId(userId),
                pullRequestRecordRepository.countByUserIdAndStatus(userId, PullRequestStatus.DRAFT_READY),
                pullRequestRecordRepository.countByUserIdAndStatus(userId, PullRequestStatus.OPEN),
                pullRequestRecordRepository.countByUserIdAndStatus(userId, PullRequestStatus.FAILED)
        );
    }

    @Transactional(readOnly = true)
    public DashboardRunMetricsResponse runMetrics(Long userId, int requestedDays) {
        int days = Math.max(1, Math.min(30, requestedDays));
        Instant to = Instant.now();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate firstDay = today.minusDays(days - 1L);
        Instant from = firstDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<AgentRun> runs = agentRunRepository.findDashboardRuns(userId, from);
        Map<LocalDate, MutableRunTrend> trends = new LinkedHashMap<>();
        for (int offset = 0; offset < days; offset += 1) {
            LocalDate date = firstDay.plusDays(offset);
            trends.put(date, new MutableRunTrend(date));
        }

        long successRuns = 0;
        long failedRuns = 0;
        long cancelledRuns = 0;
        long runningRuns = 0;
        long completedRuns = 0;
        long totalDurationSeconds = 0;

        for (AgentRun run : runs) {
            if (run.getStatus() == AgentRunStatus.SUCCESS) {
                successRuns += 1;
            } else if (run.getStatus() == AgentRunStatus.FAILED) {
                failedRuns += 1;
            } else if (run.getStatus() == AgentRunStatus.CANCELLED) {
                cancelledRuns += 1;
            } else if (run.getStatus() == AgentRunStatus.RUNNING) {
                runningRuns += 1;
            }

            long durationSeconds = durationSeconds(run);
            if (durationSeconds >= 0) {
                completedRuns += 1;
                totalDurationSeconds += durationSeconds;
            }

            LocalDate runDate = LocalDate.ofInstant(run.getStartedAt(), ZoneOffset.UTC);
            MutableRunTrend trend = trends.get(runDate);
            if (trend != null) {
                trend.record(run, durationSeconds);
            }
        }

        long averageDurationSeconds = average(totalDurationSeconds, completedRuns);
        long successRatePercent = average(successRuns * 100, completedRuns);
        List<DashboardRunTrendPointResponse> trendResponses = new ArrayList<>();
        for (MutableRunTrend trend : trends.values()) {
            trendResponses.add(trend.toResponse());
        }

        return new DashboardRunMetricsResponse(
                days,
                from,
                to,
                runs.size(),
                successRuns,
                failedRuns,
                cancelledRuns,
                runningRuns,
                completedRuns,
                averageDurationSeconds,
                successRatePercent,
                trendResponses
        );
    }

    @Transactional(readOnly = true)
    public List<DashboardActivityItemResponse> activity(Long userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        return agentStepRepository.findDashboardActivity(userId, PageRequest.of(0, limit))
                .stream()
                .map(DashboardSummaryService::activityItem)
                .toList();
    }

    private static DashboardActivityItemResponse activityItem(AgentStep step) {
        AgentRun run = step.getAgentRun();
        var task = run.getAgentTask();
        Project project = task.getProject();
        String label = step.getStepName();
        String status = step.getStatus().name();
        return new DashboardActivityItemResponse(
                step.getId(),
                run.getId(),
                task.getId(),
                project.getId(),
                project.getRepoFullName(),
                task.getTitle(),
                task.getStatus().name(),
                "AGENT_STEP",
                label,
                status,
                label + " " + status,
                occurredAt(step)
        );
    }

    private static Instant occurredAt(AgentStep step) {
        if (step.getFinishedAt() != null) {
            return step.getFinishedAt();
        }
        return step.getStartedAt();
    }

    private static long durationSeconds(AgentRun run) {
        if (run.getFinishedAt() == null || run.getStartedAt() == null) {
            return -1;
        }
        long durationSeconds = Duration.between(run.getStartedAt(), run.getFinishedAt()).toSeconds();
        return Math.max(durationSeconds, 0);
    }

    private static long average(long total, long count) {
        if (count == 0) {
            return 0;
        }
        return Math.round((double) total / count);
    }

    private static final class MutableRunTrend {
        private final LocalDate date;
        private long totalRuns;
        private long successRuns;
        private long failedRuns;
        private long cancelledRuns;
        private long runningRuns;
        private long completedRuns;
        private long totalDurationSeconds;

        private MutableRunTrend(LocalDate date) {
            this.date = date;
        }

        private void record(AgentRun run, long durationSeconds) {
            totalRuns += 1;
            if (run.getStatus() == AgentRunStatus.SUCCESS) {
                successRuns += 1;
            } else if (run.getStatus() == AgentRunStatus.FAILED) {
                failedRuns += 1;
            } else if (run.getStatus() == AgentRunStatus.CANCELLED) {
                cancelledRuns += 1;
            } else if (run.getStatus() == AgentRunStatus.RUNNING) {
                runningRuns += 1;
            }
            if (durationSeconds >= 0) {
                completedRuns += 1;
                totalDurationSeconds += durationSeconds;
            }
        }

        private DashboardRunTrendPointResponse toResponse() {
            return new DashboardRunTrendPointResponse(
                    date.toString(),
                    totalRuns,
                    successRuns,
                    failedRuns,
                    cancelledRuns,
                    runningRuns,
                    average(totalDurationSeconds, completedRuns)
            );
        }
    }
}
