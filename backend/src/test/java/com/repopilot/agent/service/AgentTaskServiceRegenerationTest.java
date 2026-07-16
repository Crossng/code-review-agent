package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentRunReportSnapshotRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.agent.worker.AgentWorkerGateway;
import com.repopilot.agent.worker.AgentWorkerStartResult;
import com.repopilot.common.ApiException;
import com.repopilot.indexer.domain.CodeChunkType;
import com.repopilot.indexer.domain.CodeSymbolType;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.modelcall.domain.ModelCallLog;
import com.repopilot.indexer.service.CodeSearchService;
import com.repopilot.modelcall.repository.ModelCallLogRepository;
import com.repopilot.modelcall.service.ModelCallLogService;
import com.repopilot.notification.service.TaskStreamService;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.patch.service.PatchGenerationService;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.service.SandboxTestService;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import com.repopilot.toolcall.service.ToolCallLogService;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AgentTaskServiceRegenerationTest {

    @Mock
    private AgentTaskRepository agentTaskRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private AgentRunReportSnapshotRepository agentRunReportSnapshotRepository;

    @Mock
    private AgentStepRepository agentStepRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CodeSearchService codeSearchService;

    @Mock
    private PatchGenerationService patchGenerationService;

    @Mock
    private PatchRecordRepository patchRecordRepository;

    @Mock
    private SandboxTestService sandboxTestService;

    @Mock
    private PatchRepairService patchRepairService;

    @Mock
    private ToolCallLogRepository toolCallLogRepository;

    @Mock
    private ModelCallLogRepository modelCallLogRepository;

    @Mock
    private TaskStreamService taskStreamService;

    @Mock
    private ProjectWriteGuardService projectWriteGuardService;

    @Mock
    private AgentWorkerGateway agentWorkerGateway;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private AgentRun savedRun;
    private AgentTask savedTask;

    private AgentTaskService agentTaskService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        agentTaskService = new AgentTaskService(
                agentTaskRepository,
                agentRunRepository,
                agentRunReportSnapshotRepository,
                agentStepRepository,
                projectRepository,
                userRepository,
                codeSearchService,
                patchGenerationService,
                patchRecordRepository,
                sandboxTestService,
                new PatchRiskReviewService(),
                new PatchDiffSafetyService(),
                patchRepairService,
                new ModelCallLogService(modelCallLogRepository, objectMapper),
                new ToolCallLogService(toolCallLogRepository, objectMapper),
                modelCallLogRepository,
                toolCallLogRepository,
                taskStreamService,
                projectWriteGuardService,
                agentWorkerGateway,
                new SyncTaskExecutor(),
                transactionManager,
                objectMapper
        );
        lenient().when(agentRunRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                setId(run, 200L);
            }
            savedRun = run;
            return run;
        });
        lenient().when(agentRunRepository.findById(200L)).thenAnswer(invocation -> Optional.ofNullable(savedRun));
        lenient().when(agentStepRepository.save(any(AgentStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(agentTaskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> {
            savedTask = invocation.getArgument(0);
            return savedTask;
        });
        lenient().when(agentTaskRepository.findStatusById(any())).thenAnswer(invocation -> Optional.ofNullable(savedTask).map(AgentTask::getStatus));
        lenient().when(patchRecordRepository.save(any(PatchRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void regeneratePatchCreatesNewRunAndPatchFromWaitingApproval() {
        Fixture fixture = fixture(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        stubSuccessfulPipeline(fixture);

        PatchRecord patch = agentTaskService.regeneratePatch(fixture.task().getId(), fixture.user().getId());

        assertThat(patch.getStatus()).isEqualTo(PatchStatus.APPLIED);
        assertThat(patch.getSummary()).isEqualTo("Regenerated patch");
        assertThat(fixture.task().getCurrentRun()).isNotNull();
        assertThat(fixture.task().getCurrentRun().getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(fixture.task().getCurrentRun().getFinishedAt()).isNotNull();
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
    }

    @Test
    void regeneratePatchRejectsPullRequestCreationStatus() {
        Fixture fixture = fixture(AgentTaskStatus.CREATING_PULL_REQUEST);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));

        assertThatThrownBy(() -> agentTaskService.regeneratePatch(fixture.task().getId(), fixture.user().getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Patch cannot be regenerated");
    }

    @Test
    void runRepairsMissingTestDependencyBeforeApproval() {
        Fixture fixture = fixture(AgentTaskStatus.CREATED);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(codeSearchService.search(any(), anyString(), anyInt()))
                .thenAnswer(invocation -> new CodeSearchResponse(invocation.getArgument(1), invocation.getArgument(2), List.of()));

        PatchRecord initialPatch = patch(fixture.task(), new AgentRun(fixture.task()), 300L, "Broken patch without test dependency");
        PatchRecord repairedPatch = patch(fixture.task(), new AgentRun(fixture.task()), 301L, "修复尝试 1：补充缺失的 Spring Boot test 依赖后重新运行沙箱测试。");
        when(patchGenerationService.generatePatch(any(AgentTask.class), any(AgentRun.class), any()))
                .thenAnswer(invocation -> patch(invocation.getArgument(0), invocation.getArgument(1), 300L, "Broken patch without test dependency"));
        when(patchRepairService.repairMavenFailure(any(), any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> patch(invocation.getArgument(0), invocation.getArgument(1), 301L, repairedPatch.getSummary()));

        SandboxTestService.SandboxWorkspace workspace = new SandboxTestService.SandboxWorkspace(
                Path.of("/tmp/repopilot-repair/run"),
                Path.of("/tmp/repopilot-repair/run/source"),
                Path.of("/tmp/repopilot-repair/run/patch.diff")
        );
        when(sandboxTestService.prepareWorkspace(any(AgentRun.class), any(PatchRecord.class))).thenReturn(workspace);
        when(sandboxTestService.applyPatch(workspace)).thenReturn(new SandboxTestService.CommandResult(
                "git apply ../patch.diff",
                0,
                false,
                10,
                "",
                "/tmp/repopilot-repair/run/patch-apply.log"
        ));
        when(sandboxTestService.runMavenTest(any(AgentRun.class), any(PatchRecord.class), any()))
                .thenAnswer(invocation -> {
                    PatchRecord patch = invocation.getArgument(1);
                    boolean repaired = patch.getSummary().startsWith("修复尝试");
                    TestRun testRun = new TestRun(
                            invocation.getArgument(0),
                            patch,
                            "mvn -q test",
                            repaired ? 0 : 1,
                            100,
                            repaired ? "" : "package org.junit.jupiter.api does not exist",
                            repaired ? TestRunStatus.PASSED : TestRunStatus.FAILED
                    );
                    setId(testRun, repaired ? 402L : 401L);
                    return testRun;
                });

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        verify(patchRepairService).repairMavenFailure(any(), any(), any(), any(), anyInt());
    }

    @Test
    void runRecordsPatchGenerationPromptWithRetrievedContextCandidates() {
        Fixture fixture = fixture(AgentTaskStatus.CREATED);
        stubSuccessfulPipeline(fixture);
        CodeSearchResultResponse result = new CodeSearchResultResponse(
                10L,
                "src/main/java/com/example/demo/user/UserController.java",
                CodeChunkType.CLASS,
                CodeSymbolType.CONTROLLER,
                "UserController",
                "com.example.demo.user.UserController",
                8,
                28,
                "User REST endpoints",
                "class UserController { List<UserEntity> listUsers() { return userService.listUsers(); } }"
        );
        when(codeSearchService.search(any(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    if (query.equals(fixture.task().getTitle())) {
                        return new CodeSearchResponse(query, limit, List.of(result));
                    }
                    return new CodeSearchResponse(query, limit, List.of());
                });

        agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        ArgumentCaptor<ModelCallLog> modelCallCaptor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(modelCallLogRepository, atLeastOnce()).save(modelCallCaptor.capture());
        ModelCallLog generatePatchCall = modelCallCaptor.getAllValues().stream()
                .filter(call -> call.getStepName().equals("generate_patch"))
                .findFirst()
                .orElseThrow();
        assertThat(generatePatchCall.getPromptJson())
                .contains("SPRING_RECIPE_CODER_WITH_RETRIEVAL_PLAN_FALLBACK")
                .contains("\"retrievedChunkCount\":1")
                .contains("src/main/java/com/example/demo/user/UserController.java")
                .contains("com.example.demo.user.UserController")
                .contains("User REST endpoints");
    }

    @Test
    void runHandsOffToAgentWorkerPrimaryExecutionWhenBridgeIsReady() {
        Fixture fixture = fixture(AgentTaskStatus.CREATED);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(agentWorkerGateway.isEnabled()).thenReturn(true);
        when(agentWorkerGateway.isPrimaryExecutionReady()).thenReturn(true);
        when(agentWorkerGateway.startRun(any(AgentRun.class)))
                .thenReturn(new AgentWorkerStartResult(
                        200L,
                        true,
                        "QUEUED",
                        List.of("load_task_context", "ensure_index", "plan_task")
                ));

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.GENERATING_PATCH);
        verify(agentWorkerGateway).startRun(any(AgentRun.class));
        verify(codeSearchService, never()).search(any(), anyString(), anyInt());
        verify(patchGenerationService, never()).generatePatch(any(), any(), any());
        verify(sandboxTestService, never()).prepareWorkspace(any(), any());
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentStepRepository, atLeastOnce()).save(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues())
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("agent_worker_start");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
                    assertThat(step.getOutputJson())
                            .contains("\"run_id\":200")
                            .contains("\"accepted\":true")
                            .contains("\"graph_nodes\"")
                            .contains("\"execution_mode\":\"WORKER_PRIMARY\"");
                });
        assertThat(stepCaptor.getAllValues())
                .noneSatisfy(step -> assertThat(step.getStepName()).isEqualTo("waiting_human_approval"));
    }

    @Test
    void runKeepsLocalExecutorFallbackWhenAgentWorkerStartFails() {
        Fixture fixture = fixture(AgentTaskStatus.CREATED);
        stubSuccessfulPipeline(fixture);
        when(agentWorkerGateway.isEnabled()).thenReturn(true);
        when(agentWorkerGateway.isPrimaryExecutionReady()).thenReturn(true);
        when(agentWorkerGateway.startRun(any(AgentRun.class)))
                .thenThrow(new ApiException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "AGENT_WORKER_START_FAILED",
                        "worker unavailable"
                ));

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentStepRepository, atLeastOnce()).save(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues())
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("agent_worker_start");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.FAILED);
                    assertThat(step.getErrorMessage()).isEqualTo("worker unavailable");
                    assertThat(step.getOutputJson()).contains("AGENT_WORKER_START_FAILED");
                })
                .anySatisfy(step -> assertThat(step.getStepName()).isEqualTo("waiting_human_approval"));
    }

    @Test
    void runStopsUnsafePatchBeforePreparingSandbox() {
        Fixture fixture = fixture(AgentTaskStatus.CREATED);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(codeSearchService.search(any(), anyString(), anyInt()))
                .thenAnswer(invocation -> new CodeSearchResponse(invocation.getArgument(1), invocation.getArgument(2), List.of()));
        when(patchGenerationService.generatePatch(any(AgentTask.class), any(AgentRun.class), any()))
                .thenAnswer(invocation -> {
                    AgentTask task = invocation.getArgument(0);
                    AgentRun run = invocation.getArgument(1);
                    PatchRecord patch = new PatchRecord(
                            task,
                            run,
                            "main",
                            "repopilot/task-" + task.getId(),
                            """
                                    diff --git a/../outside.txt b/../outside.txt
                                    --- a/../outside.txt
                                    +++ b/../outside.txt
                                    @@ -1 +1 @@
                                    +unsafe
                                    """,
                            "Unsafe patch"
                    );
                    setId(patch, 300L);
                    return patch;
                });

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("补丁 diff 未通过安全预检");
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.FAILED_PATCH_GENERATION);
        verify(sandboxTestService, never()).prepareWorkspace(any(), any());
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(agentStepRepository, atLeastOnce()).save(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues())
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("validate_patch_safety");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.FAILED);
                    assertThat(step.getErrorMessage()).isEqualTo("补丁 diff 未通过安全预检");
                });
    }

    @Test
    void runStopsWhenTaskIsCancelledBeforePatchGeneration() {
        Fixture fixture = fixture(AgentTaskStatus.CREATED);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(agentTaskRepository.findStatusById(fixture.task().getId())).thenReturn(Optional.of(AgentTaskStatus.CANCELLED));

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.CANCELLED);
        verify(patchGenerationService, never()).generatePatch(any(), any(), any());
    }

    @Test
    void cancelMarksCurrentRunningRunCancelled() {
        Fixture fixture = fixture(AgentTaskStatus.GENERATING_PATCH);
        AgentRun run = new AgentRun(fixture.task());
        setId(run, 220L);
        fixture.task().setCurrentRun(run);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));

        AgentTask cancelledTask = agentTaskService.cancel(fixture.task().getId(), fixture.user().getId());

        assertThat(cancelledTask.getStatus()).isEqualTo(AgentTaskStatus.CANCELLED);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    private void stubSuccessfulPipeline(Fixture fixture) {
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(codeSearchService.search(any(), anyString(), anyInt()))
                .thenAnswer(invocation -> new CodeSearchResponse(invocation.getArgument(1), invocation.getArgument(2), List.of()));
        when(patchGenerationService.generatePatch(any(AgentTask.class), any(AgentRun.class), any()))
                .thenAnswer(invocation -> {
                    AgentTask task = invocation.getArgument(0);
                    AgentRun run = invocation.getArgument(1);
                    PatchRecord patch = new PatchRecord(
                            task,
                            run,
                            "main",
                            "repopilot/task-" + task.getId(),
                            "diff --git a/README.md b/README.md\n",
                            "Regenerated patch"
                    );
                    setId(patch, 300L);
                    return patch;
                });
        SandboxTestService.SandboxWorkspace workspace = new SandboxTestService.SandboxWorkspace(
                Path.of("/tmp/repopilot-regenerate/run"),
                Path.of("/tmp/repopilot-regenerate/run/source"),
                Path.of("/tmp/repopilot-regenerate/run/patch.diff")
        );
        when(sandboxTestService.prepareWorkspace(any(AgentRun.class), any(PatchRecord.class))).thenReturn(workspace);
        when(sandboxTestService.applyPatch(workspace)).thenReturn(new SandboxTestService.CommandResult(
                "git apply ../patch.diff",
                0,
                false,
                10,
                "",
                "/tmp/repopilot-regenerate/run/patch-apply.log"
        ));
        when(sandboxTestService.runMavenTest(any(AgentRun.class), any(PatchRecord.class), any()))
                .thenAnswer(invocation -> {
                    TestRun testRun = new TestRun(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            "mvn -q test",
                            0,
                            100,
                            "",
                            TestRunStatus.PASSED
                    );
                    setId(testRun, 400L);
                    return testRun;
                });
    }

    private Fixture fixture(AgentTaskStatus status) {
        User user = new User("regen@example.test", "hash", "Regenerate", "USER");
        setId(user, 100L);
        Project project = new Project(user, "file:///demo", "demo", "main");
        setId(project, 110L);
        project.setLocalPath("/workspace/repos/110/source");
        AgentTask task = new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "Add User pagination API",
                "Add a paginated query API for the User module."
        );
        setId(task, 120L);
        task.setStatus(status);
        return new Fixture(user, task);
    }

    private PatchRecord patch(AgentTask task, AgentRun run, Long id, String summary) {
        PatchRecord patch = new PatchRecord(
                task,
                run,
                "main",
                "repopilot/task-" + task.getId(),
                """
                        diff --git a/src/test/java/com/example/demo/user/UserServiceTest.java b/src/test/java/com/example/demo/user/UserServiceTest.java
                        new file mode 100644
                        index 0000000..1111111
                        --- /dev/null
                        +++ b/src/test/java/com/example/demo/user/UserServiceTest.java
                        @@ -0,0 +1,7 @@
                        +package com.example.demo.user;
                        +
                        +import org.junit.jupiter.api.Test;
                        +
                        +class UserServiceTest {
                        +    @Test void compiles() {}
                        +}
                        """,
                summary
        );
        setId(patch, id);
        return patch;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }

    private record Fixture(User user, AgentTask task) {
    }
}
