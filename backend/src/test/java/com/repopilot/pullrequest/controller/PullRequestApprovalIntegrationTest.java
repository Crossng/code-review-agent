package com.repopilot.pullrequest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
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

@SpringBootTest(properties = "repopilot.agent-worker.callback-token=test-worker-callback-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PullRequestApprovalIntegrationTest {

    private static final Path TEST_WORKSPACE = Path.of("../target/repopilot-http-it-workspace")
            .toAbsolutePath()
            .normalize();

    private static final String ORIGINAL_README = "hello from repopilot\n";
    private static final String PATCH_DIFF = """
            diff --git a/README.md b/README.md
            --- a/README.md
            +++ b/README.md
            @@ -1 +1,2 @@
             hello from repopilot
            +prepared by approval integration
            """;

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
    private TestRunRepository testRunRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String email;
    private Path repositoryPath;

    @BeforeEach
    void setUp() {
        email = "approval-pr-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanupDatabase();
        if (repositoryPath != null) {
            deleteDirectory(repositoryPath.getParent());
        }
    }

    @Test
    void approvalUnlocksLocalPullRequestPreparation() throws Exception {
        String token = register(email);
        Fixture fixture = createFixture(TestRunStatus.PASSED);

        mockMvc.perform(get("/api/tasks/{taskId}/pull-request/preflight", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.canPrepare").value(false))
                .andExpect(jsonPath("$.data.taskStatus").value("WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data.latestPatchStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.latestTestStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.blockers[0]").value("准备 PR 前需要先审批已测试通过的补丁。"));

        mockMvc.perform(post("/api/tasks/{taskId}/pull-request", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PATCH_NOT_APPROVED"));

        mockMvc.perform(post("/api/tasks/{taskId}/approval/approve", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "patchId", fixture.patch().getId(),
                                "comment", "测试已通过，同意准备 PR。"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("APPROVE"))
                .andExpect(jsonPath("$.data.patchStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.taskStatus").value("CREATING_PULL_REQUEST"));

        mockMvc.perform(get("/api/tasks/{taskId}/pull-request/preflight", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPrepare").value(true))
                .andExpect(jsonPath("$.data.publishMode").value("LOCAL_DRAFT_ONLY"))
                .andExpect(jsonPath("$.data.localDraftReady").value(true))
                .andExpect(jsonPath("$.data.remotePublishingEnabled").value(false))
                .andExpect(jsonPath("$.data.remotePublishingWillRun").value(false))
                .andExpect(jsonPath("$.data.remoteReady").value(true))
                .andExpect(jsonPath("$.data.latestPatchStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.latestTestStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.blockers").isEmpty());

        mockMvc.perform(get("/api/tasks/{taskId}/approval", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].comment").value("测试已通过，同意准备 PR。"));

        MvcResult prepareResult = mockMvc.perform(post("/api/tasks/{taskId}/pull-request", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"))
                .andExpect(jsonPath("$.data.baseBranch").value("main"))
                .andExpect(jsonPath("$.data.targetBranch").value(fixture.patch().getTargetBranch()))
                .andReturn();

        JsonNode pullRequest = data(prepareResult);
        String commitSha = pullRequest.path("commitSha").asText();
        assertThat(commitSha).isNotBlank();
        assertThat(git(repositoryPath, "branch", "--show-current")).isEqualTo("main");
        assertThat(git(repositoryPath, "show", commitSha + ":README.md"))
                .contains("hello from repopilot")
                .contains("prepared by approval integration");

        mockMvc.perform(get("/api/tasks/{taskId}/pull-request", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commitSha").value(commitSha))
                .andExpect(jsonPath("$.data.status").value("DRAFT_READY"));

        mockMvc.perform(get("/api/tasks/{taskId}/pull-request/preflight", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPrepare").value(false))
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"))
                .andExpect(jsonPath("$.data.localDraftReady").value(true))
                .andExpect(jsonPath("$.data.existingPullRequestStatus").value("DRAFT_READY"))
                .andExpect(jsonPath("$.data.blockers").isEmpty());
    }

    @Test
    void approvedPatchStillRequiresPassingSandboxTestBeforePullRequest() throws Exception {
        String token = register(email);
        Fixture fixture = createFixture(TestRunStatus.FAILED);
        fixture.patch().approve();
        patchRecordRepository.save(fixture.patch());
        fixture.task().setStatus(AgentTaskStatus.CREATING_PULL_REQUEST);
        agentTaskRepository.save(fixture.task());

        mockMvc.perform(post("/api/tasks/{taskId}/pull-request", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PATCH_TEST_NOT_PASSED"));
    }

    @Test
    void workerGeneratedPatchCanBeApprovedAndPreparedAsLocalPullRequest() throws Exception {
        String token = register(email);
        Fixture fixture = createWorkerFixture();

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/review",
                        fixture.task().getCurrentRun().getId(),
                        fixture.patch().getId())
                        .header("X-RepoPilot-Worker-Token", "test-worker-callback-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.patchId").value(fixture.patch().getId()))
                .andExpect(jsonPath("$.data.stepStatus").value("SUCCESS"));

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/approval-ready",
                        fixture.task().getCurrentRun().getId(),
                        fixture.patch().getId())
                        .header("X-RepoPilot-Worker-Token", "test-worker-callback-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskStatus").value("WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data.runStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.approvalStepStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.streamCompleted").value(true));

        AgentTask waitingTask = agentTaskRepository.findById(fixture.task().getId()).orElseThrow();
        AgentRun finishedRun = agentRunRepository.findById(fixture.task().getCurrentRun().getId()).orElseThrow();
        PatchRecord workerPatch = patchRecordRepository.findById(fixture.patch().getId()).orElseThrow();
        assertThat(waitingTask.getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        assertThat(finishedRun.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(workerPatch.getStatus()).isEqualTo(PatchStatus.APPLIED);
        assertThat(workerPatch.getGenerationMode()).isEqualTo("WORKER_SAFE_PLANNING_DRAFT");
        assertThat(workerPatch.getGenerationProvider()).isEqualTo("AGENT_WORKER");
        assertThat(workerPatch.getGenerationModel()).isEqualTo("worker-retrieval-plan-v1");
        assertThat(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(finishedRun.getId()))
                .extracting(AgentStep::getStepName)
                .contains("review_patch", "waiting_human_approval");

        mockMvc.perform(get("/api/tasks/{taskId}/pull-request/preflight", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPrepare").value(false))
                .andExpect(jsonPath("$.data.taskStatus").value("WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data.latestPatchStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.latestTestStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.blockers[0]").value("准备 PR 前需要先审批已测试通过的补丁。"));

        mockMvc.perform(post("/api/tasks/{taskId}/approval/approve", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "patchId", fixture.patch().getId(),
                                "comment", "Worker 补丁已通过测试和审查，同意准备 PR。"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("APPROVE"))
                .andExpect(jsonPath("$.data.patchStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.taskStatus").value("CREATING_PULL_REQUEST"));

        mockMvc.perform(get("/api/tasks/{taskId}/pull-request/preflight", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canPrepare").value(true))
                .andExpect(jsonPath("$.data.latestPatchStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.latestTestStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.localDraftReady").value(true))
                .andExpect(jsonPath("$.data.blockers").isEmpty());

        MvcResult prepareResult = mockMvc.perform(post("/api/tasks/{taskId}/pull-request", fixture.task().getId())
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT_READY"))
                .andExpect(jsonPath("$.data.taskStatus").value("DONE"))
                .andExpect(jsonPath("$.data.targetBranch").value(fixture.patch().getTargetBranch()))
                .andReturn();

        JsonNode pullRequest = data(prepareResult);
        String commitSha = pullRequest.path("commitSha").asText();
        assertThat(commitSha).isNotBlank();
        assertThat(git(repositoryPath, "show", commitSha + ":README.md"))
                .contains("hello from repopilot")
                .contains("prepared by worker patch");
        assertThat(git(repositoryPath, "show", "-s", "--format=%B", commitSha))
                .contains("由 RepoPilot 生成。")
                .contains("补丁：#" + fixture.patch().getId())
                .contains("测试：mvn test 已通过");
    }

    private Fixture createFixture(TestRunStatus testStatus) throws Exception {
        User owner = userRepository.findByEmail(email).orElseThrow();
        repositoryPath = createRepository();
        Project project = new Project(owner, repositoryPath.toUri().toString(), "example/pr-http", "main");
        project.setLocalPath(repositoryPath.toString());
        project.setStatus(ProjectStatus.READY);
        project = projectRepository.save(project);

        AgentTask task = agentTaskRepository.save(new AgentTask(
                project,
                owner,
                AgentTaskType.FEATURE,
                "Prepare approval PR",
                "Prepare a pull request through the HTTP approval gate."
        ));
        AgentRun run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task.setStatus(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        task = agentTaskRepository.save(task);

        PatchRecord patch = new PatchRecord(
                task,
                run,
                "main",
                "repopilot/http-it-" + task.getId(),
                PATCH_DIFF,
                "Update README through approval integration test"
        );
        patch.markApplied();
        patch = patchRecordRepository.save(patch);
        testRunRepository.save(new TestRun(
                run,
                patch,
                "mvn -q test",
                testStatus == TestRunStatus.PASSED ? 0 : 1,
                1200,
                testStatus == TestRunStatus.PASSED ? "" : "failing test",
                testStatus
        ));
        return new Fixture(task, patch);
    }

    private Fixture createWorkerFixture() throws Exception {
        User owner = userRepository.findByEmail(email).orElseThrow();
        repositoryPath = createRepository();
        Project project = new Project(owner, repositoryPath.toUri().toString(), "example/worker-pr-http", "main");
        project.setLocalPath(repositoryPath.toString());
        project.setStatus(ProjectStatus.READY);
        project = projectRepository.save(project);

        AgentTask task = agentTaskRepository.save(new AgentTask(
                project,
                owner,
                AgentTaskType.FEATURE,
                "Worker patch approval PR",
                "Prepare a pull request from a Worker-generated patch."
        ));
        AgentRun run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task = agentTaskRepository.save(task);

        String workerDiff = """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                 hello from repopilot
                +prepared by worker patch
                """;
        PatchRecord patch = new PatchRecord(
                task,
                run,
                "main",
                "repopilot/worker-http-it-" + task.getId(),
                workerDiff,
                "Worker 生成并通过审查的补丁",
                "WORKER_SAFE_PLANNING_DRAFT",
                "AGENT_WORKER",
                "worker-retrieval-plan-v1"
        );
        patch.markApplied();
        patch = patchRecordRepository.save(patch);
        testRunRepository.save(new TestRun(
                run,
                patch,
                "mvn -q test",
                0,
                1200,
                "BUILD SUCCESS",
                TestRunStatus.PASSED
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "validate_patch_safety",
                AgentStepStatus.SUCCESS,
                json(Map.of("patchId", patch.getId(), "source", "agent-worker")),
                json(Map.of("safe", true, "changedPaths", List.of("README.md"), "findings", List.of()))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "apply_patch",
                AgentStepStatus.SUCCESS,
                json(Map.of("patchId", patch.getId(), "source", "agent-worker")),
                json(Map.of("exitCode", 0))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "run_tests",
                AgentStepStatus.SUCCESS,
                json(Map.of("patchId", patch.getId(), "source", "agent-worker")),
                json(Map.of("status", "PASSED", "exitCode", 0))
        ));
        return new Fixture(task, patch);
    }

    private Path createRepository() throws IOException, InterruptedException {
        Path repoRoot = TEST_WORKSPACE.resolve("repos").resolve("approval-pr-" + UUID.randomUUID()).resolve("source");
        Files.createDirectories(repoRoot);
        git(repoRoot, "init");
        git(repoRoot, "checkout", "-b", "main");
        Files.writeString(repoRoot.resolve("README.md"), ORIGINAL_README, StandardCharsets.UTF_8);
        git(repoRoot, "add", "README.md");
        git(repoRoot, "-c", "user.name=RepoPilot Test", "-c", "user.email=repopilot-test@example.local", "commit", "-m", "Initial commit");
        return repoRoot;
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Approval PR"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return data(result).path("token").asText();
    }

    private void cleanupDatabase() {
        jdbcTemplate.update("""
                update agent_task
                set current_run_id = null
                where user_id in (select id from app_user where email = ?)
                """, email);
        jdbcTemplate.update("""
                delete from pull_request_record
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from approval_record
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from test_run
                where agent_run_id in (
                    select run.id
                    from agent_run run
                    join agent_task task on task.id = run.agent_task_id
                    where task.user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from patch_record
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from tool_call_log
                where agent_run_id in (
                    select run.id
                    from agent_run run
                    join agent_task task on task.id = run.agent_task_id
                    where task.user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from model_call_log
                where agent_run_id in (
                    select run.id
                    from agent_run run
                    join agent_task task on task.id = run.agent_task_id
                    where task.user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from agent_step
                where agent_run_id in (
                    select run.id
                    from agent_run run
                    join agent_task task on task.id = run.agent_task_id
                    where task.user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("""
                delete from agent_run
                where agent_task_id in (
                    select id from agent_task where user_id in (select id from app_user where email = ?)
                )
                """, email);
        jdbcTemplate.update("delete from agent_task where user_id in (select id from app_user where email = ?)", email);
        jdbcTemplate.update("delete from project where owner_user_id in (select id from app_user where email = ?)", email);
        jdbcTemplate.update("delete from app_user where email = ?", email);
    }

    private JsonNode data(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String git(Path workingDirectory, String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command(args))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output.trim();
    }

    private List<String> command(String... args) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of("git"),
                java.util.Arrays.stream(args)
        ).toList();
    }

    private void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private record Fixture(AgentTask task, PatchRecord patch) {
    }
}
