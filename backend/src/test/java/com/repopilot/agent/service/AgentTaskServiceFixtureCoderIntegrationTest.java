package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import com.repopilot.indexer.domain.CodeChunkType;
import com.repopilot.indexer.domain.CodeSymbolType;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.indexer.service.CodeSearchService;
import com.repopilot.modelcall.domain.ModelCallLog;
import com.repopilot.modelcall.repository.ModelCallLogRepository;
import com.repopilot.modelcall.service.ModelCallLogService;
import com.repopilot.notification.service.TaskStreamService;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.patch.service.CoderPatchOutputParser;
import com.repopilot.patch.service.ConfiguredCoderModelClient;
import com.repopilot.patch.service.PatchGenerationService;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
import com.repopilot.sandbox.service.SandboxTestService;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import com.repopilot.toolcall.service.ToolCallLogService;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AgentTaskServiceFixtureCoderIntegrationTest {

    private static final Path DEMO_REPOSITORY = Path.of("..", "examples", "demo-spring-repo").normalize();
    private static final String FIXTURE_DIFF = """
            diff --git a/README.md b/README.md
            new file mode 100644
            index 0000000..1111111
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1,2 @@
            +# Fixture Coder Patch
            +Generated through the RepoPilot fixture Coder model path.
            """;
    private static final String OPENAI_COMPATIBLE_DIFF = """
            diff --git a/README.md b/README.md
            new file mode 100644
            index 0000000..2222222
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1,2 @@
            +# OpenAI Compatible Coder Patch
            +Generated through the RepoPilot OpenAI-compatible Coder model path.
            """;

    @TempDir
    private Path tempDir;

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
    private PatchRecordRepository patchRecordRepository;

    @Mock
    private TestRunRepository testRunRepository;

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

    private final List<AgentStep> savedSteps = new ArrayList<>();
    private final List<PatchRecord> savedPatches = new ArrayList<>();
    private final List<TestRun> savedTestRuns = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1_000L);

    private AgentTask savedTask;
    private AgentRun savedRun;
    private AgentTaskService agentTaskService;
    private ObjectMapper objectMapper;
    private HttpServer coderServer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        configureAgentTaskService(new ConfiguredCoderModelClient(
                objectMapper,
                "fixture",
                FIXTURE_DIFF,
                "https://api.openai.com/v1",
                "",
                "",
                120,
                4096,
                "developer",
                "",
                ""
        ));

        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        lenient().when(agentRunRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            assignIdIfNeeded(run);
            savedRun = run;
            return run;
        });
        lenient().when(agentRunRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedRun));
        lenient().when(agentTaskRepository.save(any(AgentTask.class))).thenAnswer(invocation -> {
            savedTask = invocation.getArgument(0);
            return savedTask;
        });
        lenient().when(agentTaskRepository.findStatusById(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedTask).map(AgentTask::getStatus));
        lenient().when(agentStepRepository.save(any(AgentStep.class))).thenAnswer(invocation -> {
            AgentStep step = invocation.getArgument(0);
            assignIdIfNeeded(step);
            savedSteps.add(step);
            return step;
        });
        lenient().when(patchRecordRepository.save(any(PatchRecord.class))).thenAnswer(invocation -> {
            PatchRecord patch = invocation.getArgument(0);
            assignIdIfNeeded(patch);
            savedPatches.add(patch);
            return patch;
        });
        lenient().when(testRunRepository.save(any(TestRun.class))).thenAnswer(invocation -> {
            TestRun testRun = invocation.getArgument(0);
            assignIdIfNeeded(testRun);
            savedTestRuns.add(testRun);
            return testRun;
        });
        lenient().when(codeSearchService.search(any(), anyString(), anyInt()))
                .thenAnswer(invocation -> new CodeSearchResponse(invocation.getArgument(1), invocation.getArgument(2), List.of()));
    }

    @AfterEach
    void tearDown() {
        if (coderServer != null) {
            coderServer.stop(0);
        }
    }

    private void configureAgentTaskService(ConfiguredCoderModelClient coderModelClient) {
        PatchGenerationService patchGenerationService = new PatchGenerationService(
                patchRecordRepository,
                new CoderPatchOutputParser(),
                coderModelClient
        );
        SandboxTestService sandboxTestService = new SandboxTestService(
                testRunRepository,
                tempDir.resolve("workspace").toString(),
                Path.of("..", ".m2").toString(),
                "maven:3.9-eclipse-temurin-17",
                600
        );
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
    }

    @Test
    void fixtureCoderPatchRunsThroughParserSafetySandboxAndReview() throws IOException {
        Fixture fixture = fixture();
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        PatchRecord patch = savedPatches.stream()
                .filter(candidate -> PatchGenerationService.MODE_LLM_CODER_DRAFT.equals(candidate.getGenerationMode()))
                .findFirst()
                .orElseThrow();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        assertThat(patch.getStatus()).isEqualTo(PatchStatus.APPLIED);
        assertThat(patch.getGenerationProvider()).isEqualTo("LOCAL_FIXTURE");
        assertThat(patch.getGenerationModel()).isEqualTo("fixture-coder");
        assertThat(patch.getDiffContent())
                .startsWith("diff --git a/README.md b/README.md")
                .contains("Fixture Coder Patch")
                .doesNotContain("```");
        ArgumentCaptor<ModelCallLog> modelCallCaptor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(modelCallLogRepository, atLeastOnce()).save(modelCallCaptor.capture());
        assertThat(modelCallCaptor.getAllValues())
                .anySatisfy(call -> {
                    assertThat(call.getStepName()).isEqualTo("generate_patch");
                    assertThat(call.getModelProvider()).isEqualTo("LOCAL_FIXTURE");
                    assertThat(call.getModelName()).isEqualTo("fixture-coder");
                    assertThat(call.getResponseJson()).contains("\"generationProvider\":\"LOCAL_FIXTURE\"");
                });
        assertThat(savedTestRuns)
                .hasSize(1)
                .allSatisfy(testRun -> {
                    assertThat(testRun.getStatus()).isEqualTo(TestRunStatus.PASSED);
                    assertThat(testRun.getPatch()).isSameAs(patch);
                    assertThat(testRun.getCommand()).contains("mvn -q test");
                });
        assertThat(savedSteps)
                .extracting(AgentStep::getStepName)
                .containsSubsequence(
                        "generate_patch",
                        "validate_patch_safety",
                        "apply_patch",
                        "run_tests",
                        "review_patch",
                        "waiting_human_approval"
                );
        assertThat(savedSteps)
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("generate_patch");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
                    assertThat(step.getOutputJson()).contains(PatchGenerationService.MODE_LLM_CODER_DRAFT);
                })
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("validate_patch_safety");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
                    assertThat(step.getOutputJson()).contains("\"safe\":true").contains("README.md");
                })
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("review_patch");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
                    assertThat(step.getOutputJson()).contains("没有自动审查发现。");
                });
        Path sandboxReadme = tempDir.resolve("workspace")
                .resolve("runs")
                .resolve(String.valueOf(run.getId()))
                .resolve("source")
                .resolve("README.md");
        assertThat(sandboxReadme).exists();
        assertThat(Files.readString(sandboxReadme))
                .contains("Generated through the RepoPilot fixture Coder model path.");
    }

    @Test
    void openAiCompatibleCoderPatchRunsThroughHttpClientParserSafetySandboxAndReview() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startCoderServer("gpt-repopilot-test", OPENAI_COMPATIBLE_DIFF, authorization, requestBody);
        configureAgentTaskService(new ConfiguredCoderModelClient(
                objectMapper,
                "openai-compatible",
                "",
                coderServerBaseUrl(),
                "test-coder-key",
                "gpt-repopilot-test",
                30,
                2048,
                "developer",
                "",
                ""
        ));
        Fixture fixture = fixture();
        stubRetrievedContext(fixture);
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));

        AgentRun run = agentTaskService.run(fixture.task().getId(), fixture.user().getId());

        PatchRecord patch = savedPatches.stream()
                .filter(candidate -> PatchGenerationService.MODE_LLM_CODER_DRAFT.equals(candidate.getGenerationMode()))
                .findFirst()
                .orElseThrow();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(fixture.task().getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        assertThat(patch.getStatus()).isEqualTo(PatchStatus.APPLIED);
        assertThat(patch.getGenerationProvider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(patch.getGenerationModel()).isEqualTo("gpt-repopilot-test");
        assertThat(patch.getDiffContent())
                .startsWith("diff --git a/README.md b/README.md")
                .contains("OpenAI Compatible Coder Patch")
                .doesNotContain("```");
        assertThat(authorization.get()).isEqualTo("Bearer test-coder-key");
        assertThat(requestBody.get())
                .contains("\"model\":\"gpt-repopilot-test\"")
                .contains("Return only one raw unified diff")
                .contains(fixture.task().getTitle())
                .contains("src/main/java/com/example/demo/user/UserController.java")
                .contains("com.example.demo.user.UserController");
        ArgumentCaptor<ModelCallLog> modelCallCaptor = ArgumentCaptor.forClass(ModelCallLog.class);
        verify(modelCallLogRepository, atLeastOnce()).save(modelCallCaptor.capture());
        assertThat(modelCallCaptor.getAllValues())
                .anySatisfy(call -> {
                    assertThat(call.getStepName()).isEqualTo("generate_patch");
                    assertThat(call.getModelProvider()).isEqualTo("OPENAI_COMPATIBLE");
                    assertThat(call.getModelName()).isEqualTo("gpt-repopilot-test");
                    assertThat(call.getPromptJson()).contains("src/main/java/com/example/demo/user/UserController.java");
                    assertThat(call.getResponseJson()).contains("\"generationProvider\":\"OPENAI_COMPATIBLE\"");
                });
        assertThat(savedSteps)
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("generate_patch");
                    assertThat(step.getOutputJson())
                            .contains("\"generationProvider\":\"OPENAI_COMPATIBLE\"")
                            .contains("\"generationModel\":\"gpt-repopilot-test\"");
                })
                .anySatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("waiting_human_approval");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.PENDING);
                });
        assertThat(savedTestRuns)
                .hasSize(1)
                .allSatisfy(testRun -> assertThat(testRun.getStatus()).isEqualTo(TestRunStatus.PASSED));
        Path sandboxReadme = tempDir.resolve("workspace")
                .resolve("runs")
                .resolve(String.valueOf(run.getId()))
                .resolve("source")
                .resolve("README.md");
        assertThat(sandboxReadme).exists();
        assertThat(Files.readString(sandboxReadme))
                .contains("Generated through the RepoPilot OpenAI-compatible Coder model path.");
    }

    private void stubRetrievedContext(Fixture fixture) {
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
    }

    private void startCoderServer(
            String model,
            String rawDiff,
            AtomicReference<String> authorization,
            AtomicReference<String> requestBody
    ) throws IOException {
        coderServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        coderServer.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of(
                    "model", model,
                    "choices", List.of(Map.of("message", Map.of("content", rawDiff)))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        coderServer.start();
    }

    private String coderServerBaseUrl() {
        return "http://127.0.0.1:" + coderServer.getAddress().getPort() + "/v1";
    }

    private Fixture fixture() throws IOException {
        User user = new User("fixture-coder@example.test", "hash", "Fixture Coder", "USER");
        setId(user, 100L);
        Path repositoryPath = tempDir.resolve("repo").resolve("source");
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Project project = new Project(user, repositoryPath.toUri().toString(), "fixture/demo", "main");
        setId(project, 110L);
        project.setLocalPath(repositoryPath.toString());
        AgentTask task = new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "Update project notes",
                "Add a short repository note generated by the configured Coder model."
        );
        setId(task, 120L);
        savedTask = task;
        return new Fixture(user, task);
    }

    private void copyDirectory(Path sourceRoot, Path targetRoot) throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            for (Path source : stream.toList()) {
                Path relative = sourceRoot.relativize(source);
                if (relative.toString().startsWith(".git")) {
                    continue;
                }
                Path target = targetRoot.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void assignIdIfNeeded(Object target) {
        Object currentId = ReflectionTestUtils.getField(target, "id");
        if (currentId == null) {
            setId(target, ids.incrementAndGet());
        }
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }

    private record Fixture(User user, AgentTask task) {
    }
}
