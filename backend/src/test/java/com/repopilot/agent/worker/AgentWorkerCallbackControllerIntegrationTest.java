package com.repopilot.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
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
import com.repopilot.modelcall.domain.ModelCallLog;
import com.repopilot.modelcall.domain.ModelCallStatus;
import com.repopilot.modelcall.repository.ModelCallLogRepository;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
import com.repopilot.sandbox.service.SandboxTestService;
import com.repopilot.toolcall.domain.ToolCallLog;
import com.repopilot.toolcall.domain.ToolCallStatus;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "repopilot.agent-worker.callback-token=test-worker-callback-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentWorkerCallbackControllerIntegrationTest {

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
    private ToolCallLogRepository toolCallLogRepository;

    @Autowired
    private ModelCallLogRepository modelCallLogRepository;

    @Autowired
    private PatchRecordRepository patchRecordRepository;

    @Autowired
    private TestRunRepository testRunRepository;

    @MockBean
    private SandboxTestService sandboxTestService;

    private String email;
    private Project project;
    private AgentTask task;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        email = "worker-callback-" + UUID.randomUUID() + "@example.test";
        User user = userRepository.save(new User(email, "hash", "Worker Callback", "USER"));
        project = projectRepository.save(new Project(user, "file:///worker-callback", "worker/callback", "main"));
        project.setLocalPath("/workspace/repos/worker-callback/source");
        project = projectRepository.save(project);
        task = agentTaskRepository.save(new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "Worker callback task",
                "Record worker step evidence."
        ));
        run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task = agentTaskRepository.save(task);
    }

    @AfterEach
    void tearDown() {
        if (run != null && run.getId() != null) {
            testRunRepository.deleteAll(testRunRepository.findByAgentRunIdOrderByCreatedAtDesc(run.getId()));
            toolCallLogRepository.deleteAll(toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId()));
            modelCallLogRepository.deleteAll(modelCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId()));
            agentStepRepository.deleteAll(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId()));
        }
        if (task != null && task.getId() != null) {
            patchRecordRepository.deleteAll(patchRecordRepository.findByAgentTaskIdOrderByCreatedAtDesc(task.getId()));
        }
        if (task != null && task.getId() != null) {
            agentTaskRepository.findById(task.getId()).ifPresent(savedTask -> {
                savedTask.setCurrentRun(null);
                agentTaskRepository.save(savedTask);
            });
        }
        if (run != null && run.getId() != null) {
            agentRunRepository.findById(run.getId()).ifPresent(agentRunRepository::delete);
        }
        if (task != null && task.getId() != null) {
            agentTaskRepository.findById(task.getId()).ifPresent(agentTaskRepository::delete);
        }
        if (project != null && project.getId() != null) {
            projectRepository.findById(project.getId()).ifPresent(projectRepository::delete);
        }
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    @Test
    void recordStepRequiresCallbackTokenAndPersistsStepEvidence() throws Exception {
        Map<String, Object> request = Map.of(
                "step_name", "worker_plan_task",
                "status", "SUCCESS",
                "input", Map.of("taskId", task.getId(), "source", "agent-worker"),
                "output", Map.of("summary", "Worker generated a plan", "graph_nodes", List.of("plan_task")),
                "error_message", ""
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/steps", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stepName").value("worker_plan_task"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.outputJson").value(org.hamcrest.Matchers.containsString("Worker generated a plan")));

        List<AgentStep> steps = agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(steps)
                .hasSize(1)
                .allSatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("worker_plan_task");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
                    assertThat(jsonNode(step.getInputJson()).path("source").asText()).isEqualTo("agent-worker");
                    assertThat(jsonNode(step.getOutputJson()).path("summary").asText()).isEqualTo("Worker generated a plan");
                    assertThat(step.getFinishedAt()).isNotNull();
                });
    }

    @Test
    void recordStepRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "step_name", "worker_plan_task",
                "status", "SUCCESS",
                "input", Map.of(),
                "output", Map.of("summary", "Worker generated a plan")
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/steps", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void recordToolCallRequiresCallbackTokenAndPersistsSanitizedToolEvidence() throws Exception {
        Map<String, Object> request = Map.of(
                "tool_name", "read_project_file",
                "status", "SUCCESS",
                "input", Map.of(
                        "path", "src/main/java/com/example/UserController.java",
                        "authorization", "Bearer worker-api-key-value"
                ),
                "output", Map.of("path", "src/main/java/com/example/UserController.java", "size", 2048),
                "duration_ms", 17
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/tool-calls", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.toolName").value("read_project_file"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.durationMs").value(17));

        List<ToolCallLog> logs = toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(logs).hasSize(1);
        ToolCallLog log = logs.get(0);
        assertThat(log.getToolName()).isEqualTo("read_project_file");
        assertThat(log.getStatus()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(log.getDurationMs()).isEqualTo(17);
        assertThat(log.getInputJson()).contains("[REDACTED]");
        assertThat(log.getInputJson()).doesNotContain("worker-api-key-value");
        assertThat(jsonNode(log.getOutputJson()).path("size").asInt()).isEqualTo(2048);
    }

    @Test
    void recordToolCallRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "tool_name", "search_code",
                "status", "FAILED",
                "input", Map.of("query", "User"),
                "error_message", "tool failed"
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/tool-calls", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void recordModelCallRequiresCallbackTokenAndPersistsSanitizedModelEvidence() throws Exception {
        Map<String, Object> request = Map.of(
                "step_name", "generate_patch",
                "model_provider", "OPENAI_COMPATIBLE",
                "model_name", "gpt-test-coder",
                "status", "SUCCESS",
                "prompt", Map.of(
                        "instruction", "Generate a safe patch",
                        "api_key", "worker-api-key-value"
                ),
                "response", Map.of("summary", "Generated draft patch"),
                "prompt_tokens", 12,
                "completion_tokens", 8,
                "total_tokens", 20,
                "duration_ms", 33
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/model-calls", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stepName").value("generate_patch"))
                .andExpect(jsonPath("$.data.modelProvider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.modelName").value("gpt-test-coder"))
                .andExpect(jsonPath("$.data.totalTokens").value(20));

        List<ModelCallLog> logs = modelCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(logs).hasSize(1);
        ModelCallLog log = logs.get(0);
        assertThat(log.getStepName()).isEqualTo("generate_patch");
        assertThat(log.getModelProvider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(log.getModelName()).isEqualTo("gpt-test-coder");
        assertThat(log.getStatus()).isEqualTo(ModelCallStatus.SUCCESS);
        assertThat(log.getPromptTokens()).isEqualTo(12);
        assertThat(log.getCompletionTokens()).isEqualTo(8);
        assertThat(log.getTotalTokens()).isEqualTo(20);
        assertThat(log.getPromptJson()).contains("[REDACTED]");
        assertThat(log.getPromptJson()).doesNotContain("worker-api-key-value");
        assertThat(jsonNode(log.getResponseJson()).path("summary").asText()).isEqualTo("Generated draft patch");
    }

    @Test
    void updateStatusRequiresCallbackTokenAndPersistsTaskAndRunStatus() throws Exception {
        Map<String, Object> request = Map.of(
                "task_status", "WAITING_HUMAN_APPROVAL",
                "run_status", "SUCCESS",
                "stream_message", "Worker 已进入人工审批",
                "complete_stream", true
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/status", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value(task.getId()))
                .andExpect(jsonPath("$.data.taskStatus").value("WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.runStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.streamCompleted").value(true));

        AgentTask savedTask = agentTaskRepository.findById(task.getId()).orElseThrow();
        AgentRun savedRun = agentRunRepository.findById(run.getId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        assertThat(savedRun.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(savedRun.getFinishedAt()).isNotNull();
        assertThat(savedRun.getErrorMessage()).isNull();
    }

    @Test
    void recordPatchRequiresCallbackTokenAndPersistsWorkerPatchDraft() throws Exception {
        String diff = """
                diff --git a/.repopilot/worker-plan.md b/.repopilot/worker-plan.md
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/.repopilot/worker-plan.md
                @@ -0,0 +1,2 @@
                +# Worker patch draft
                +来自 Python Worker 的补丁草稿。
                """;
        Map<String, Object> request = Map.of(
                "diff_content", diff,
                "summary", "Worker 生成的补丁草稿",
                "generation_mode", "WORKER_SAFE_PLANNING_DRAFT",
                "generation_provider", "AGENT_WORKER",
                "generation_model", "worker-retrieval-plan-v1"
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.agentTaskId").value(task.getId()))
                .andExpect(jsonPath("$.data.agentRunId").value(run.getId()))
                .andExpect(jsonPath("$.data.baseBranch").value("main"))
                .andExpect(jsonPath("$.data.targetBranch").value("repopilot/task-" + task.getId()))
                .andExpect(jsonPath("$.data.generationMode").value("WORKER_SAFE_PLANNING_DRAFT"))
                .andExpect(jsonPath("$.data.generationProvider").value("AGENT_WORKER"))
                .andExpect(jsonPath("$.data.generationModel").value("worker-retrieval-plan-v1"))
                .andExpect(jsonPath("$.data.status").value("GENERATED"));

        List<PatchRecord> patches = patchRecordRepository.findByAgentTaskIdOrderByCreatedAtDesc(task.getId());
        assertThat(patches).hasSize(1);
        PatchRecord patch = patches.get(0);
        assertThat(patch.getAgentRun().getId()).isEqualTo(run.getId());
        assertThat(patch.getBaseBranch()).isEqualTo("main");
        assertThat(patch.getTargetBranch()).isEqualTo("repopilot/task-" + task.getId());
        assertThat(patch.getDiffContent()).contains("Worker patch draft");
        assertThat(patch.getSummary()).isEqualTo("Worker 生成的补丁草稿");
        assertThat(patch.getGenerationMode()).isEqualTo("WORKER_SAFE_PLANNING_DRAFT");
        assertThat(patch.getGenerationProvider()).isEqualTo("AGENT_WORKER");
        assertThat(patch.getGenerationModel()).isEqualTo("worker-retrieval-plan-v1");
        assertThat(patch.getStatus()).isEqualTo(PatchStatus.GENERATED);
    }

    @Test
    void recordPatchRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "diff_content", "diff --git a/a.txt b/a.txt\n",
                "summary", "Worker patch",
                "generation_mode", "WORKER_SAFE_PLANNING_DRAFT",
                "generation_provider", "AGENT_WORKER"
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(patchRecordRepository.findByAgentTaskIdOrderByCreatedAtDesc(task.getId())).isEmpty();
    }

    @Test
    void validatePatchSafetyRequiresCallbackTokenAndRecordsSafetyStep() throws Exception {
        PatchRecord patch = saveWorkerSafePatch();

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/safety", run.getId(), patch.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.patchId").value(patch.getId()))
                .andExpect(jsonPath("$.data.agentTaskId").value(task.getId()))
                .andExpect(jsonPath("$.data.agentRunId").value(run.getId()))
                .andExpect(jsonPath("$.data.safe").value(true))
                .andExpect(jsonPath("$.data.changedPaths[0]").value(".repopilot/worker-plan.md"))
                .andExpect(jsonPath("$.data.findings").isArray())
                .andExpect(jsonPath("$.data.stepId").exists())
                .andExpect(jsonPath("$.data.stepStatus").value("SUCCESS"));

        List<AgentStep> steps = agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(steps).hasSize(1);
        AgentStep step = steps.get(0);
        assertThat(step.getStepName()).isEqualTo("validate_patch_safety");
        assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(jsonNode(step.getInputJson()).path("patchId").asLong()).isEqualTo(patch.getId());
        assertThat(jsonNode(step.getInputJson()).path("source").asText()).isEqualTo("agent-worker");
        assertThat(jsonNode(step.getOutputJson()).path("safe").asBoolean()).isTrue();
        assertThat(jsonNode(step.getOutputJson()).path("changedPaths").get(0).asText())
                .isEqualTo(".repopilot/worker-plan.md");
        assertThat(step.getErrorMessage()).isNull();

        assertThat(agentTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentTaskStatus.CREATED);
        assertThat(agentRunRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void validatePatchSafetyRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        PatchRecord patch = saveWorkerSafePatch();

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/safety", run.getId(), patch.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void runPatchSandboxTestsRequiresPassedSafetyAndRecordsSandboxEvidence() throws Exception {
        PatchRecord patch = saveWorkerSafePatch();
        saveWorkerSafetyStep(patch);
        SandboxTestService.SandboxWorkspace workspace = new SandboxTestService.SandboxWorkspace(
                Path.of("/tmp/repopilot-worker-sandbox/run"),
                Path.of("/tmp/repopilot-worker-sandbox/run/source"),
                Path.of("/tmp/repopilot-worker-sandbox/run/patch.diff")
        );
        SandboxTestService.CommandResult applyResult = new SandboxTestService.CommandResult(
                "git apply ../patch.diff",
                0,
                false,
                12,
                "",
                "/tmp/repopilot-worker-sandbox/run/patch-apply.log"
        );
        when(sandboxTestService.prepareWorkspace(any(AgentRun.class), any(PatchRecord.class))).thenReturn(workspace);
        when(sandboxTestService.applyPatch(eq(workspace))).thenReturn(applyResult);
        when(sandboxTestService.runMavenTest(any(AgentRun.class), any(PatchRecord.class), eq(workspace)))
                .thenAnswer(invocation -> testRunRepository.save(new TestRun(
                        run,
                        patch,
                        "mvn -q test",
                        0,
                        45,
                        "BUILD SUCCESS",
                        TestRunStatus.PASSED
                )));

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/sandbox-tests", run.getId(), patch.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.patchId").value(patch.getId()))
                .andExpect(jsonPath("$.data.patchStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.testsPassed").value(true))
                .andExpect(jsonPath("$.data.applyStepStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.testStepStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.testStatus").value("PASSED"))
                .andExpect(jsonPath("$.data.applyResult.exitCode").value(0))
                .andExpect(jsonPath("$.data.testRun.exitCode").value(0));

        PatchRecord savedPatch = patchRecordRepository.findById(patch.getId()).orElseThrow();
        assertThat(savedPatch.getStatus()).isEqualTo(PatchStatus.APPLIED);
        List<TestRun> testRuns = testRunRepository.findByAgentRunIdOrderByCreatedAtDesc(run.getId());
        assertThat(testRuns).hasSize(1);
        assertThat(testRuns.get(0).getStatus()).isEqualTo(TestRunStatus.PASSED);

        List<AgentStep> steps = agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(steps).extracting(AgentStep::getStepName)
                .containsExactly("validate_patch_safety", "apply_patch", "run_tests");
        AgentStep applyStep = steps.get(1);
        assertThat(applyStep.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(jsonNode(applyStep.getInputJson()).path("source").asText()).isEqualTo("agent-worker");
        assertThat(jsonNode(applyStep.getOutputJson()).path("exitCode").asInt()).isEqualTo(0);
        AgentStep testStep = steps.get(2);
        assertThat(testStep.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(jsonNode(testStep.getOutputJson()).path("status").asText()).isEqualTo("PASSED");

        List<ToolCallLog> toolCalls = toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(toolCalls).extracting(ToolCallLog::getToolName)
                .containsExactly("prepare_sandbox", "apply_patch", "run_maven_test");
        assertThat(toolCalls).allSatisfy(toolCall -> assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.SUCCESS));
        assertThat(agentTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentTaskStatus.CREATED);
        assertThat(agentRunRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void runPatchSandboxTestsRejectsPatchWithoutPassedSafety() throws Exception {
        PatchRecord patch = saveWorkerSafePatch();

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/sandbox-tests", run.getId(), patch.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PATCH_SAFETY_NOT_PASSED"));

        assertThat(patchRecordRepository.findById(patch.getId()).orElseThrow().getStatus())
                .isEqualTo(PatchStatus.GENERATED);
        assertThat(testRunRepository.findByAgentRunIdOrderByCreatedAtDesc(run.getId())).isEmpty();
        assertThat(toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void runPatchSandboxTestsRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        PatchRecord patch = saveWorkerSafePatch();

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/sandbox-tests", run.getId(), patch.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())).isEmpty();
        assertThat(testRunRepository.findByAgentRunIdOrderByCreatedAtDesc(run.getId())).isEmpty();
    }

    @Test
    void updateStatusRejectsEmptyStatusPayload() throws Exception {
        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/status", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stream_message", "nothing to update"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_STATUS_EMPTY"));
    }

    @Test
    void updateStatusRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "task_status", "FAILED_PATCH_GENERATION",
                "run_status", "FAILED",
                "error_message", "worker failed"
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/status", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(agentTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentTaskStatus.CREATED);
        assertThat(agentRunRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    private JsonNode jsonNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private PatchRecord saveWorkerSafePatch() {
        String diff = """
                diff --git a/.repopilot/worker-plan.md b/.repopilot/worker-plan.md
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/.repopilot/worker-plan.md
                @@ -0,0 +1,2 @@
                +# Worker safe patch
                +安全预检样例。
                """;
        return patchRecordRepository.save(new PatchRecord(
                task,
                run,
                "main",
                "repopilot/task-" + task.getId(),
                diff,
                "Worker 安全预检样例",
                "WORKER_SAFE_PLANNING_DRAFT",
                "AGENT_WORKER",
                "worker-retrieval-plan-v1"
        ));
    }

    private void saveWorkerSafetyStep(PatchRecord patch) throws Exception {
        agentStepRepository.save(new AgentStep(
                run,
                "validate_patch_safety",
                AgentStepStatus.SUCCESS,
                jsonString(Map.of("patchId", patch.getId(), "source", "agent-worker")),
                jsonString(Map.of("safe", true, "changedPaths", List.of(".repopilot/worker-plan.md"), "findings", List.of()))
        ));
    }

    private String jsonString(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
