package com.repopilot.patch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.indexer.domain.CodeChunkType;
import com.repopilot.indexer.domain.CodeSymbolType;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchGenerationServiceTest {

    private static final Path DEMO_REPOSITORY = Path.of("..", "examples", "demo-spring-repo").normalize();

    @Mock
    private PatchRecordRepository patchRecordRepository;

    private PatchGenerationService patchGenerationService;

    @BeforeEach
    void setUp() {
        when(patchRecordRepository.save(any(PatchRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        patchGenerationService = new PatchGenerationService(
                patchRecordRepository,
                new CoderPatchOutputParser(),
                request -> Optional.empty()
        );
    }

    @Test
    void generatePatchCreatesRealUserPaginationDiffForDemoRepository(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        AgentTask task = taskFor(
                repositoryPath,
                "Add User pagination API",
                "Add a paginated query API for the User module and preserve existing style."
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getSummary()).isEqualTo("Adds GET /api/users/page with service/mapper pagination and unit tests.");
        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SPRING_USER_PAGINATION_RECIPE);
        assertThat(patch.getDiffContent())
                .contains("+++ b/pom.xml")
                .contains("spring-boot-starter-test")
                .contains("+++ b/src/main/java/com/example/demo/user/UserController.java")
                .contains("@GetMapping(\"/page\")")
                .contains("@RequestParam(defaultValue = \"0\") int page")
                .contains("+++ b/src/main/java/com/example/demo/user/UserService.java")
                .contains("listUsersPage(int page, int size)")
                .contains("+++ b/src/main/java/com/example/demo/user/UserMapper.java")
                .contains("findPage(int offset, int size)")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("listUsersPageReturnsRequestedSlice")
                .doesNotContain(".repopilot/task-");
    }

    @Test
    void generatePatchCreatesUserIdValidationDiffForDemoRepository(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        AgentTask task = taskFor(
                repositoryPath,
                "Fix User id validation bug",
                "修复 User 模块 getUser 参数校验 bug，拒绝空 id 和非正数 id。"
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getSummary()).isEqualTo("Adds User id validation guard with unit tests.");
        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SPRING_USER_ID_VALIDATION_RECIPE);
        assertThat(patch.getDiffContent())
                .contains("+++ b/pom.xml")
                .contains("spring-boot-starter-test")
                .contains("+++ b/src/main/java/com/example/demo/user/UserService.java")
                .contains("if (id == null || id < 1)")
                .contains("User id must be positive")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("getUserRejectsMissingId")
                .contains("getUserRejectsNonPositiveId")
                .doesNotContain(".repopilot/task-");
    }

    @Test
    void generatePatchCreatesUserCountDiffForDemoRepository(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        AgentTask task = taskFor(
                repositoryPath,
                "Add User count API",
                "给 User 模块新增统计用户总数接口。"
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getSummary()).isEqualTo("Adds GET /api/users/count with service/mapper count logic and unit tests.");
        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SPRING_USER_COUNT_RECIPE);
        assertThat(patch.getDiffContent())
                .contains("+++ b/pom.xml")
                .contains("spring-boot-starter-test")
                .contains("+++ b/src/main/java/com/example/demo/user/UserController.java")
                .contains("@GetMapping(\"/count\")")
                .contains("public long countUsers()")
                .contains("+++ b/src/main/java/com/example/demo/user/UserService.java")
                .contains("return userMapper.countAll();")
                .contains("+++ b/src/main/java/com/example/demo/user/UserMapper.java")
                .contains("public long countAll()")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("countUsersReturnsTotalNumberOfUsers")
                .doesNotContain(".repopilot/task-");
    }

    @Test
    void generatePatchCreatesUserCreateDiffForDemoRepository(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        AgentTask task = taskFor(
                repositoryPath,
                "Add User create API",
                "给 User 模块新增创建用户接口，接收 name 并返回创建结果。"
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getSummary())
                .isEqualTo("Adds POST /api/users with create request DTO, service/mapper creation logic, and unit tests.");
        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SPRING_USER_CREATE_RECIPE);
        assertThat(patch.getDiffContent())
                .contains("+++ b/pom.xml")
                .contains("spring-boot-starter-test")
                .contains("+++ b/src/main/java/com/example/demo/user/UserController.java")
                .contains("@PostMapping")
                .contains("public UserEntity createUser(@RequestBody CreateUserRequest request)")
                .contains("+++ b/src/main/java/com/example/demo/user/CreateUserRequest.java")
                .contains("public class CreateUserRequest")
                .contains("+++ b/src/main/java/com/example/demo/user/UserService.java")
                .contains("User name must not be blank")
                .contains("return userMapper.save(request.getName());")
                .contains("+++ b/src/main/java/com/example/demo/user/UserMapper.java")
                .contains("public UserEntity save(String name)")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("createUserReturnsCreatedUser")
                .contains("createUserRejectsBlankName")
                .doesNotContain(".repopilot/task-");
    }

    @Test
    void generatePatchDoesNotRequirePomChangeWhenTestDependencyAlreadyExists(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Path pomPath = repositoryPath.resolve("pom.xml");
        Files.writeString(pomPath, Files.readString(pomPath).replace(
                "    </dependencies>",
                """
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>"""
        ));
        AgentTask task = taskFor(
                repositoryPath,
                "Fix User id validation bug",
                "Add validation for negative User id values."
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getSummary()).isEqualTo("Adds User id validation guard with unit tests.");
        assertThat(patch.getDiffContent())
                .doesNotContain("+++ b/pom.xml")
                .contains("+++ b/src/main/java/com/example/demo/user/UserService.java")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java");
    }

    @Test
    void generatePatchAppendsValidationCoverageWhenUserServiceTestAlreadyExists(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Path testPath = repositoryPath.resolve("src/test/java/com/example/demo/user/UserServiceTest.java");
        Files.createDirectories(testPath.getParent());
        Files.writeString(testPath, """
                package com.example.demo.user;

                import static org.assertj.core.api.Assertions.assertThat;

                import org.junit.jupiter.api.Test;

                class UserServiceTest {

                    private final UserService userService = new UserService(new UserMapper());

                    @Test
                    void getUserReturnsRequestedId() {
                        UserEntity user = userService.getUser(1L);

                        assertThat(user.getId()).isEqualTo(1L);
                    }
                }
                """);
        AgentTask task = taskFor(
                repositoryPath,
                "Fix User id validation bug",
                "修复 User id 参数校验。"
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getSummary()).isEqualTo("Adds User id validation guard with unit tests.");
        assertThat(patch.getDiffContent())
                .contains("--- a/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("assertThatThrownBy")
                .contains("getUserRejectsMissingId")
                .contains("getUserRejectsNonPositiveId");
        assertThat(patch.getDiffContent())
                .doesNotContain("new file mode 100644\n--- /dev/null\n+++ b/src/test/java/com/example/demo/user/UserServiceTest.java");
    }

    @Test
    void generatePatchAppendsCountCoverageWhenUserServiceTestAlreadyExists(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Path testPath = repositoryPath.resolve("src/test/java/com/example/demo/user/UserServiceTest.java");
        Files.createDirectories(testPath.getParent());
        Files.writeString(testPath, """
                package com.example.demo.user;

                import static org.assertj.core.api.Assertions.assertThat;

                import org.junit.jupiter.api.Test;

                class UserServiceTest {

                    private final UserService userService = new UserService(new UserMapper());

                    @Test
                    void getUserReturnsRequestedId() {
                        UserEntity user = userService.getUser(1L);

                        assertThat(user.getId()).isEqualTo(1L);
                    }
                }
                """);
        AgentTask task = taskFor(
                repositoryPath,
                "Add total User count",
                "新增 User 数量统计能力。"
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SPRING_USER_COUNT_RECIPE);
        assertThat(patch.getDiffContent())
                .contains("--- a/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("countUsersReturnsTotalNumberOfUsers");
        assertThat(patch.getDiffContent())
                .doesNotContain("new file mode 100644\n--- /dev/null\n+++ b/src/test/java/com/example/demo/user/UserServiceTest.java");
    }

    @Test
    void generatePatchAppendsCreateCoverageWhenUserServiceTestAlreadyExists(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Path testPath = repositoryPath.resolve("src/test/java/com/example/demo/user/UserServiceTest.java");
        Files.createDirectories(testPath.getParent());
        Files.writeString(testPath, """
                package com.example.demo.user;

                import static org.assertj.core.api.Assertions.assertThat;

                import org.junit.jupiter.api.Test;

                class UserServiceTest {

                    private final UserService userService = new UserService(new UserMapper());

                    @Test
                    void getUserReturnsRequestedId() {
                        UserEntity user = userService.getUser(1L);

                        assertThat(user.getId()).isEqualTo(1L);
                    }
                }
                """);
        AgentTask task = taskFor(
                repositoryPath,
                "Create new User API",
                "新增 User 创建接口并校验 name。"
        );
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SPRING_USER_CREATE_RECIPE);
        assertThat(patch.getDiffContent())
                .contains("--- a/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("+++ b/src/test/java/com/example/demo/user/UserServiceTest.java")
                .contains("assertThatThrownBy")
                .contains("createUserReturnsCreatedUser")
                .contains("createUserRejectsBlankName");
        assertThat(patch.getDiffContent())
                .doesNotContain("new file mode 100644\n--- /dev/null\n+++ b/src/test/java/com/example/demo/user/UserServiceTest.java");
    }

    @Test
    void generatePatchFallsBackToPlanningNotesForUnrecognizedTask(@TempDir Path repositoryPath) {
        AgentTask task = taskFor(repositoryPath, "Rename project banner", "Update the README headline.");
        AgentRun run = new AgentRun(task);
        List<CodeSearchResultResponse> retrievedResults = List.of(
                new CodeSearchResultResponse(
                        10L,
                        "src/main/java/com/example/demo/BannerController.java",
                        CodeChunkType.CLASS,
                        CodeSymbolType.CONTROLLER,
                        "BannerController",
                        "com.example.demo.BannerController",
                        12,
                        32,
                        "Handles banner display endpoints | includes current headline.",
                        "class BannerController { String headline() { return \"Old\"; } }"
                ),
                new CodeSearchResultResponse(
                        11L,
                        "src/test/java/com/example/demo/BannerControllerTest.java",
                        CodeChunkType.CLASS,
                        CodeSymbolType.CLASS,
                        "BannerControllerTest",
                        "com.example.demo.BannerControllerTest",
                        8,
                        44,
                        null,
                        "class BannerControllerTest { void headlineUsesCurrentCopy() {} }"
                )
        );

        PatchRecord patch = patchGenerationService.generatePatch(task, run, retrievedResults);

        assertThat(patch.getSummary()).contains("Safe retrieval-grounded Coder plan");
        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_SAFE_PLANNING_FALLBACK);
        assertThat(patch.getDiffContent())
                .contains(".repopilot/task-")
                .contains("# RepoPilot Task")
                .contains("Coder Plan")
                .contains("Generation mode: `SAFE_PLANNING_FALLBACK`")
                .contains("Retrieved chunks: 2")
                .contains("## Candidate Files From Retrieval")
                .contains("`src/main/java/com/example/demo/BannerController.java`")
                .contains("com.example.demo.BannerController")
                .contains("12-32")
                .contains("Handles banner display endpoints \\| includes current headline.")
                .contains("`src/test/java/com/example/demo/BannerControllerTest.java`")
                .contains("## Suggested Edit Sequence")
                .contains("Draft the smallest unified diff")
                .contains("## Guardrails For The Next Coder Pass")
                .contains("Run PatchRiskReview before human approval.");
    }

    @Test
    void generatePatchFromCoderOutputPersistsParsedLlmDraft(@TempDir Path repositoryPath) {
        AgentTask task = taskFor(repositoryPath, "Update User docs", "Update User docs.");
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatchFromCoderOutput(task, run, """
                ```diff
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                +RepoPilot docs
                ```
                """);

        assertThat(patch.getSummary()).isEqualTo("LLM Coder draft: parsed unified diff for task #" + task.getId());
        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_LLM_CODER_DRAFT);
        assertThat(patch.getBaseBranch()).isEqualTo("main");
        assertThat(patch.getTargetBranch()).isEqualTo("repopilot/task-" + task.getId());
        assertThat(patch.getDiffContent())
                .startsWith("diff --git a/README.md b/README.md\n")
                .endsWith("\n")
                .doesNotContain("```");
    }

    @Test
    void generatePatchUsesConfiguredCoderModelBeforeSafePlanningFallback(@TempDir Path repositoryPath) {
        patchGenerationService = new PatchGenerationService(
                patchRecordRepository,
                new CoderPatchOutputParser(),
                request -> Optional.of(new CoderModelClient.CoderModelResponse(
                        "LOCAL_FIXTURE",
                        "fixture-coder",
                        """
                                diff --git a/README.md b/README.md
                                --- a/README.md
                                +++ b/README.md
                                @@ -1 +1,2 @@
                                +Generated by fixture model
                                """
                ))
        );
        AgentTask task = taskFor(repositoryPath, "Update README headline", "Update README headline.");
        AgentRun run = new AgentRun(task);

        PatchRecord patch = patchGenerationService.generatePatch(task, run, List.of());

        assertThat(patch.getGenerationMode()).isEqualTo(PatchGenerationService.MODE_LLM_CODER_DRAFT);
        assertThat(patch.getDiffContent())
                .contains("Generated by fixture model")
                .doesNotContain(".repopilot/task-");
    }

    private AgentTask taskFor(Path repositoryPath, String title, String description) {
        User user = new User("patch-test@example.test", "hash", "Patch Test", "USER");
        Project project = new Project(user, "file://demo-spring-repo", "demo-spring-repo", "main");
        project.setLocalPath(repositoryPath.toString());
        return new AgentTask(project, user, AgentTaskType.FEATURE, title, description);
    }

    private void copyDirectory(Path sourceRoot, Path targetRoot) throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            for (Path source : stream.toList()) {
                Path relative = sourceRoot.relativize(source);
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
}
