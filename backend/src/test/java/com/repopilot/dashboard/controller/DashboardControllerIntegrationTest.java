package com.repopilot.dashboard.controller;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.pullrequest.domain.PullRequestProvider;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.pullrequest.repository.PullRequestRecordRepository;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    @Autowired
    private PatchRecordRepository patchRecordRepository;

    @Autowired
    private PullRequestRecordRepository pullRequestRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String ownerEmail;
    private String otherEmail;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ownerEmail = "dashboard-owner-" + suffix + "@example.test";
        otherEmail = "dashboard-other-" + suffix + "@example.test";
    }

    @AfterEach
    void tearDown() {
        cleanupUser(ownerEmail);
        cleanupUser(otherEmail);
    }

    @Test
    void dashboardSummaryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardRunMetricsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dashboard/run-metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardActivityRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dashboard/activity"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardSummaryCountsOnlyCurrentUserWorkspace() throws Exception {
        String ownerToken = register(ownerEmail);
        register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        User other = userRepository.findByEmail(otherEmail).orElseThrow();

        Project readyProject = project(owner, "owner/ready", ProjectStatus.READY);
        project(owner, "owner/failed", ProjectStatus.FAILED);
        project(owner, "owner/created", ProjectStatus.CREATED);
        Project otherProject = project(other, "other/ready", ProjectStatus.READY);

        AgentTask createdTask = task(owner, readyProject, AgentTaskStatus.CREATED);
        task(owner, readyProject, AgentTaskStatus.PLANNING);
        task(owner, readyProject, AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        AgentTask doneTask = task(owner, readyProject, AgentTaskStatus.DONE);
        AgentTask failedTask = task(owner, readyProject, AgentTaskStatus.FAILED_TEST);
        task(owner, readyProject, AgentTaskStatus.CANCELLED);
        task(other, otherProject, AgentTaskStatus.DONE);

        pullRequest(createdTask, PullRequestStatus.DRAFT_READY);
        pullRequest(doneTask, PullRequestStatus.OPEN);
        pullRequest(failedTask, PullRequestStatus.FAILED);
        pullRequest(task(other, otherProject, AgentTaskStatus.DONE), PullRequestStatus.OPEN);

        mockMvc.perform(get("/api/dashboard/summary")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalProjects").value(3))
                .andExpect(jsonPath("$.data.readyProjects").value(1))
                .andExpect(jsonPath("$.data.failedProjects").value(1))
                .andExpect(jsonPath("$.data.totalTasks").value(6))
                .andExpect(jsonPath("$.data.createdTasks").value(1))
                .andExpect(jsonPath("$.data.runningTasks").value(1))
                .andExpect(jsonPath("$.data.waitingApprovalTasks").value(1))
                .andExpect(jsonPath("$.data.doneTasks").value(1))
                .andExpect(jsonPath("$.data.failedTasks").value(1))
                .andExpect(jsonPath("$.data.cancelledTasks").value(1))
                .andExpect(jsonPath("$.data.totalPullRequests").value(3))
                .andExpect(jsonPath("$.data.draftPullRequests").value(1))
                .andExpect(jsonPath("$.data.openPullRequests").value(1))
                .andExpect(jsonPath("$.data.failedPullRequests").value(1));

    }

    @Test
    void dashboardRunMetricsCountsRecentRunsForCurrentUser() throws Exception {
        String ownerToken = register(ownerEmail);
        register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        User other = userRepository.findByEmail(otherEmail).orElseThrow();

        Project ownerProject = project(owner, "owner/run-metrics", ProjectStatus.READY);
        Project otherProject = project(other, "other/run-metrics", ProjectStatus.READY);
        AgentTask ownerTask = task(owner, ownerProject, AgentTaskStatus.DONE);
        AgentTask ownerFailedTask = task(owner, ownerProject, AgentTaskStatus.FAILED_TEST);
        AgentTask otherTask = task(other, otherProject, AgentTaskStatus.DONE);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant todaySuccessStart = today.atTime(9, 0).toInstant(ZoneOffset.UTC);
        Instant todayFailedStart = today.atTime(10, 0).toInstant(ZoneOffset.UTC);
        Instant todayRunningStart = today.atTime(11, 0).toInstant(ZoneOffset.UTC);
        Instant yesterdayStart = today.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant oldStart = today.minusDays(8).atTime(12, 0).toInstant(ZoneOffset.UTC);

        run(ownerTask, AgentRunStatus.SUCCESS, todaySuccessStart, todaySuccessStart.plusSeconds(60));
        run(ownerFailedTask, AgentRunStatus.FAILED, todayFailedStart, todayFailedStart.plusSeconds(180));
        run(ownerTask, AgentRunStatus.RUNNING, todayRunningStart, null);
        run(ownerTask, AgentRunStatus.SUCCESS, yesterdayStart, yesterdayStart.plusSeconds(120));
        run(ownerTask, AgentRunStatus.CANCELLED, oldStart, oldStart.plusSeconds(30));
        run(otherTask, AgentRunStatus.SUCCESS, todaySuccessStart, todaySuccessStart.plusSeconds(30));

        mockMvc.perform(get("/api/dashboard/run-metrics")
                        .queryParam("days", "7")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.totalRuns").value(4))
                .andExpect(jsonPath("$.data.successRuns").value(2))
                .andExpect(jsonPath("$.data.failedRuns").value(1))
                .andExpect(jsonPath("$.data.cancelledRuns").value(0))
                .andExpect(jsonPath("$.data.runningRuns").value(1))
                .andExpect(jsonPath("$.data.completedRuns").value(3))
                .andExpect(jsonPath("$.data.averageDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.successRatePercent").value(67))
                .andExpect(jsonPath("$.data.trend.length()").value(7))
                .andExpect(jsonPath("$.data.trend[5].date").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.data.trend[5].totalRuns").value(1))
                .andExpect(jsonPath("$.data.trend[5].successRuns").value(1))
                .andExpect(jsonPath("$.data.trend[5].averageDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.trend[6].date").value(today.toString()))
                .andExpect(jsonPath("$.data.trend[6].totalRuns").value(3))
                .andExpect(jsonPath("$.data.trend[6].successRuns").value(1))
                .andExpect(jsonPath("$.data.trend[6].failedRuns").value(1))
                .andExpect(jsonPath("$.data.trend[6].runningRuns").value(1))
                .andExpect(jsonPath("$.data.trend[6].averageDurationSeconds").value(120));
    }

    @Test
    void dashboardActivityListsRecentStepsForCurrentUser() throws Exception {
        String ownerToken = register(ownerEmail);
        register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        User other = userRepository.findByEmail(otherEmail).orElseThrow();

        Project ownerProject = project(owner, "owner/activity", ProjectStatus.READY);
        Project otherProject = project(other, "other/activity", ProjectStatus.READY);
        AgentTask ownerTask = task(owner, ownerProject, AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        AgentTask otherTask = task(other, otherProject, AgentTaskStatus.DONE);
        AgentRun ownerRun = run(ownerTask, AgentRunStatus.SUCCESS, Instant.parse("2026-07-15T09:00:00Z"), Instant.parse("2026-07-15T09:03:00Z"));
        AgentRun otherRun = run(otherTask, AgentRunStatus.SUCCESS, Instant.parse("2026-07-15T09:00:00Z"), Instant.parse("2026-07-15T09:03:00Z"));

        step(ownerRun, "plan_task", AgentStepStatus.SUCCESS, Instant.parse("2026-07-15T09:00:10Z"), Instant.parse("2026-07-15T09:00:20Z"));
        step(ownerRun, "run_tests", AgentStepStatus.SUCCESS, Instant.parse("2026-07-15T09:01:00Z"), Instant.parse("2026-07-15T09:02:00Z"));
        step(ownerRun, "waiting_human_approval", AgentStepStatus.SUCCESS, Instant.parse("2026-07-15T09:03:00Z"), Instant.parse("2026-07-15T09:03:05Z"));
        step(otherRun, "other_user_step", AgentStepStatus.SUCCESS, Instant.parse("2026-07-15T09:04:00Z"), Instant.parse("2026-07-15T09:04:10Z"));

        mockMvc.perform(get("/api/dashboard/activity")
                        .queryParam("limit", "2")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].taskId").value(ownerTask.getId()))
                .andExpect(jsonPath("$.data[0].projectId").value(ownerProject.getId()))
                .andExpect(jsonPath("$.data[0].projectName").value("owner/activity"))
                .andExpect(jsonPath("$.data[0].taskTitle").value("Dashboard WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data[0].taskStatus").value("WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data[0].activityType").value("AGENT_STEP"))
                .andExpect(jsonPath("$.data[0].label").value("waiting_human_approval"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].message").value("waiting_human_approval SUCCESS"))
                .andExpect(jsonPath("$.data[0].inputJson").doesNotExist())
                .andExpect(jsonPath("$.data[0].outputJson").doesNotExist())
                .andExpect(jsonPath("$.data[1].label").value("run_tests"));
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Dashboard User"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private Project project(User owner, String name, ProjectStatus status) {
        Project project = new Project(owner, "https://github.com/" + name + ".git", name, "main");
        project.setStatus(status);
        return projectRepository.save(project);
    }

    private AgentTask task(User owner, Project project, AgentTaskStatus status) {
        AgentTask task = new AgentTask(project, owner, AgentTaskType.FEATURE, "Dashboard " + status, "Dashboard summary fixture.");
        task.setStatus(status);
        return agentTaskRepository.save(task);
    }

    private AgentRun run(AgentTask task, AgentRunStatus status, Instant startedAt, Instant finishedAt) {
        AgentRun run = agentRunRepository.saveAndFlush(new AgentRun(task));
        jdbcTemplate.update(
                "update agent_run set status = ?, started_at = ?, finished_at = ? where id = ?",
                status.name(),
                Timestamp.from(startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt),
                run.getId()
        );
        return run;
    }

    private AgentStep step(AgentRun run, String name, AgentStepStatus status, Instant startedAt, Instant finishedAt) {
        AgentStep step = agentStepRepository.saveAndFlush(new AgentStep(run, name, status, "{}", "{}"));
        jdbcTemplate.update(
                "update agent_step set status = ?, started_at = ?, finished_at = ? where id = ?",
                status.name(),
                Timestamp.from(startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt),
                step.getId()
        );
        return step;
    }

    private void pullRequest(AgentTask task, PullRequestStatus status) {
        AgentRun run = agentRunRepository.save(new AgentRun(task));
        PatchRecord patch = patchRecordRepository.save(new PatchRecord(
                task,
                run,
                "main",
                "repopilot/dashboard-" + task.getId(),
                "diff --git a/README.md b/README.md\n",
                "Dashboard fixture patch"
        ));
        pullRequestRecordRepository.save(new PullRequestRecord(
                task,
                patch,
                PullRequestProvider.GITHUB,
                "RepoPilot: dashboard fixture",
                "Prepared by RepoPilot.",
                "main",
                "repopilot/dashboard-" + task.getId(),
                "abc123",
                "RepoPilot: dashboard fixture",
                status
        ));
    }

    private void cleanupUser(String email) {
        jdbcTemplate.update("""
                delete from agent_step
                where agent_run_id in (
                    select id from agent_run where agent_task_id in (
                        select id from agent_task where user_id in (select id from app_user where email = ?)
                    )
                )
                """, email);
        jdbcTemplate.update("""
                delete from pull_request_record
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from patch_record
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from agent_run
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from agent_task
                where user_id in (select id from app_user where email = ?)
                """, email);
        jdbcTemplate.update("""
                delete from project
                where owner_user_id in (select id from app_user where email = ?)
                """, email);
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
