package com.repopilot.patch.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import org.springframework.stereotype.Service;

@Service
public class PatchGenerationService {

    private static final int FALLBACK_CONTEXT_LIMIT = 10;
    private static final int FALLBACK_PREVIEW_LIMIT = 180;

    public static final String MODE_SPRING_USER_PAGINATION_RECIPE = "SPRING_USER_PAGINATION_RECIPE";
    public static final String MODE_SPRING_USER_ID_VALIDATION_RECIPE = "SPRING_USER_ID_VALIDATION_RECIPE";
    public static final String MODE_SPRING_USER_COUNT_RECIPE = "SPRING_USER_COUNT_RECIPE";
    public static final String MODE_SPRING_USER_CREATE_RECIPE = "SPRING_USER_CREATE_RECIPE";
    public static final String MODE_LLM_CODER_DRAFT = "LLM_CODER_DRAFT";
    public static final String MODE_SAFE_PLANNING_FALLBACK = "SAFE_PLANNING_FALLBACK";

    private static final List<PatchRecipe> SPRING_RECIPES = List.of(
            new PatchRecipe(MODE_SPRING_USER_PAGINATION_RECIPE, PatchGenerationService::generateDemoUserPaginationPatch),
            new PatchRecipe(MODE_SPRING_USER_ID_VALIDATION_RECIPE, PatchGenerationService::generateDemoUserIdValidationPatch),
            new PatchRecipe(MODE_SPRING_USER_COUNT_RECIPE, PatchGenerationService::generateDemoUserCountPatch),
            new PatchRecipe(MODE_SPRING_USER_CREATE_RECIPE, PatchGenerationService::generateDemoUserCreatePatch)
    );

    private final PatchRecordRepository patchRecordRepository;
    private final CoderPatchOutputParser coderPatchOutputParser;
    private final CoderModelClient coderModelClient;

    public PatchGenerationService(
            PatchRecordRepository patchRecordRepository,
            CoderPatchOutputParser coderPatchOutputParser,
            CoderModelClient coderModelClient
    ) {
        this.patchRecordRepository = patchRecordRepository;
        this.coderPatchOutputParser = coderPatchOutputParser;
        this.coderModelClient = coderModelClient;
    }

    public PatchRecord generatePatch(
            AgentTask task,
            AgentRun run,
            List<CodeSearchResultResponse> retrievedResults
    ) {
        String baseBranch = task.getProject().getDefaultBranch();
        String targetBranch = "repopilot/task-" + task.getId();
        Optional<GeneratedPatch> generatedPatch = generateSpringRecipePatch(task);
        if (generatedPatch.isPresent()) {
            GeneratedPatch patch = generatedPatch.get();
            return patchRecordRepository.save(new PatchRecord(
                    task,
                    run,
                    baseBranch,
                    targetBranch,
                    patch.diff(),
                    patch.summary(),
                    patch.generationMode()
            ));
        }
        Optional<CoderModelClient.CoderModelResponse> coderModelResponse = coderModelClient.generatePatch(
                CoderModelClient.CoderModelRequest.from(task, retrievedResults)
        );
        if (coderModelResponse.isPresent()) {
            return generatePatchFromCoderOutput(task, run, coderModelResponse.get().rawOutput());
        }
        return generatePlanningPatch(task, run, retrievedResults, baseBranch, targetBranch);
    }

    public PatchRecord generateSafePlanningPatch(
            AgentTask task,
            AgentRun run,
            List<CodeSearchResultResponse> retrievedResults
    ) {
        return generatePatch(task, run, retrievedResults);
    }

    public PatchRecord generatePatchFromCoderOutput(
            AgentTask task,
            AgentRun run,
            String rawCoderOutput
    ) {
        CoderPatchOutputParser.ParsedCoderPatch parsedPatch = coderPatchOutputParser.parse(rawCoderOutput);
        return patchRecordRepository.save(new PatchRecord(
                task,
                run,
                task.getProject().getDefaultBranch(),
                "repopilot/task-" + task.getId(),
                parsedPatch.diffContent(),
                "LLM Coder draft: parsed unified diff for task #" + task.getId(),
                MODE_LLM_CODER_DRAFT
        ));
    }

    private PatchRecord generatePlanningPatch(
            AgentTask task,
            AgentRun run,
            List<CodeSearchResultResponse> retrievedResults,
            String baseBranch,
            String targetBranch
    ) {
        String filePath = ".repopilot/task-" + task.getId() + "-plan.md";
        List<String> lines = planLines(task, run, retrievedResults);
        String diff = newFileDiff(filePath, lines);
        String summary = "Safe retrieval-grounded Coder plan: adds RepoPilot planning notes for task #" + task.getId();
        return patchRecordRepository.save(new PatchRecord(
                task,
                run,
                baseBranch,
                targetBranch,
                diff,
                summary,
                MODE_SAFE_PLANNING_FALLBACK
        ));
    }

    private Optional<GeneratedPatch> generateSpringRecipePatch(AgentTask task) {
        for (PatchRecipe recipe : SPRING_RECIPES) {
            Optional<GeneratedPatch> generatedPatch = recipe.generator().generate(task);
            if (generatedPatch.isPresent()) {
                GeneratedPatch patch = generatedPatch.get();
                return Optional.of(new GeneratedPatch(patch.diff(), patch.summary(), recipe.name()));
            }
        }
        return Optional.empty();
    }

    private static Optional<GeneratedPatch> generateDemoUserPaginationPatch(AgentTask task) {
        if (!isUserPaginationRequest(task)) {
            return Optional.empty();
        }
        String localPath = task.getProject().getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            return Optional.empty();
        }
        Path repositoryPath = Path.of(localPath).toAbsolutePath().normalize();
        Map<String, String> updatedFiles = new LinkedHashMap<>();
        if (!replaceFileIfChanged(repositoryPath, updatedFiles, "pom.xml", PatchGenerationService::addTestDependency)) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserController.java",
                PatchGenerationService::addUserPaginationControllerEndpoint
        )) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserService.java",
                PatchGenerationService::addUserPaginationServiceMethod
        )) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserMapper.java",
                PatchGenerationService::addUserPaginationMapperMethod
        )) {
            return Optional.empty();
        }
        String testPath = "src/test/java/com/example/demo/user/UserServiceTest.java";
        if (Files.exists(repositoryPath.resolve(testPath))) {
            return Optional.empty();
        }
        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, String> entry : updatedFiles.entrySet()) {
            String oldContent = readString(repositoryPath.resolve(entry.getKey())).orElseThrow();
            diff.append(replaceFileDiff(entry.getKey(), oldContent, entry.getValue()));
        }
        diff.append(newFileDiff(testPath, lines(USER_SERVICE_TEST)));
        return Optional.of(new GeneratedPatch(
                diff.toString(),
                "Adds GET /api/users/page with service/mapper pagination and unit tests.",
                MODE_SPRING_USER_PAGINATION_RECIPE
        ));
    }

    private static Optional<GeneratedPatch> generateDemoUserIdValidationPatch(AgentTask task) {
        if (!isUserIdValidationRequest(task)) {
            return Optional.empty();
        }
        String localPath = task.getProject().getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            return Optional.empty();
        }
        Path repositoryPath = Path.of(localPath).toAbsolutePath().normalize();
        Map<String, String> updatedFiles = new LinkedHashMap<>();
        if (!replaceFileIfChanged(repositoryPath, updatedFiles, "pom.xml", PatchGenerationService::addTestDependency)) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserService.java",
                PatchGenerationService::addUserIdValidation
        )) {
            return Optional.empty();
        }
        String testPath = "src/test/java/com/example/demo/user/UserServiceTest.java";
        boolean createTestFile = !Files.exists(repositoryPath.resolve(testPath));
        if (!createTestFile && !replaceFile(
                repositoryPath,
                updatedFiles,
                testPath,
                PatchGenerationService::addUserIdValidationTests
        )) {
            return Optional.empty();
        }
        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, String> entry : updatedFiles.entrySet()) {
            String oldContent = readString(repositoryPath.resolve(entry.getKey())).orElseThrow();
            if (!oldContent.equals(entry.getValue())) {
                diff.append(replaceFileDiff(entry.getKey(), oldContent, entry.getValue()));
            }
        }
        if (createTestFile) {
            diff.append(newFileDiff(testPath, lines(USER_ID_VALIDATION_TEST)));
        }
        return Optional.of(new GeneratedPatch(
                diff.toString(),
                "Adds User id validation guard with unit tests.",
                MODE_SPRING_USER_ID_VALIDATION_RECIPE
        ));
    }

    private static Optional<GeneratedPatch> generateDemoUserCountPatch(AgentTask task) {
        if (!isUserCountRequest(task)) {
            return Optional.empty();
        }
        String localPath = task.getProject().getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            return Optional.empty();
        }
        Path repositoryPath = Path.of(localPath).toAbsolutePath().normalize();
        Map<String, String> updatedFiles = new LinkedHashMap<>();
        if (!replaceFileIfChanged(repositoryPath, updatedFiles, "pom.xml", PatchGenerationService::addTestDependency)) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserController.java",
                PatchGenerationService::addUserCountControllerEndpoint
        )) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserService.java",
                PatchGenerationService::addUserCountServiceMethod
        )) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserMapper.java",
                PatchGenerationService::addUserCountMapperMethod
        )) {
            return Optional.empty();
        }
        String testPath = "src/test/java/com/example/demo/user/UserServiceTest.java";
        boolean createTestFile = !Files.exists(repositoryPath.resolve(testPath));
        if (!createTestFile && !replaceFile(
                repositoryPath,
                updatedFiles,
                testPath,
                PatchGenerationService::addUserCountTests
        )) {
            return Optional.empty();
        }
        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, String> entry : updatedFiles.entrySet()) {
            String oldContent = readString(repositoryPath.resolve(entry.getKey())).orElseThrow();
            if (!oldContent.equals(entry.getValue())) {
                diff.append(replaceFileDiff(entry.getKey(), oldContent, entry.getValue()));
            }
        }
        if (createTestFile) {
            diff.append(newFileDiff(testPath, lines(USER_COUNT_TEST)));
        }
        return Optional.of(new GeneratedPatch(
                diff.toString(),
                "Adds GET /api/users/count with service/mapper count logic and unit tests.",
                MODE_SPRING_USER_COUNT_RECIPE
        ));
    }

    private static Optional<GeneratedPatch> generateDemoUserCreatePatch(AgentTask task) {
        if (!isUserCreateRequest(task)) {
            return Optional.empty();
        }
        String localPath = task.getProject().getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            return Optional.empty();
        }
        Path repositoryPath = Path.of(localPath).toAbsolutePath().normalize();
        Map<String, String> updatedFiles = new LinkedHashMap<>();
        if (!replaceFileIfChanged(repositoryPath, updatedFiles, "pom.xml", PatchGenerationService::addTestDependency)) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserController.java",
                PatchGenerationService::addUserCreateControllerEndpoint
        )) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserService.java",
                PatchGenerationService::addUserCreateServiceMethod
        )) {
            return Optional.empty();
        }
        if (!replaceFile(
                repositoryPath,
                updatedFiles,
                "src/main/java/com/example/demo/user/UserMapper.java",
                PatchGenerationService::addUserCreateMapperMethod
        )) {
            return Optional.empty();
        }
        String requestPath = "src/main/java/com/example/demo/user/CreateUserRequest.java";
        if (Files.exists(repositoryPath.resolve(requestPath))) {
            return Optional.empty();
        }
        String testPath = "src/test/java/com/example/demo/user/UserServiceTest.java";
        boolean createTestFile = !Files.exists(repositoryPath.resolve(testPath));
        if (!createTestFile && !replaceFile(
                repositoryPath,
                updatedFiles,
                testPath,
                PatchGenerationService::addUserCreateTests
        )) {
            return Optional.empty();
        }
        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, String> entry : updatedFiles.entrySet()) {
            String oldContent = readString(repositoryPath.resolve(entry.getKey())).orElseThrow();
            if (!oldContent.equals(entry.getValue())) {
                diff.append(replaceFileDiff(entry.getKey(), oldContent, entry.getValue()));
            }
        }
        diff.append(newFileDiff(requestPath, lines(CREATE_USER_REQUEST)));
        if (createTestFile) {
            diff.append(newFileDiff(testPath, lines(USER_CREATE_TEST)));
        }
        return Optional.of(new GeneratedPatch(
                diff.toString(),
                "Adds POST /api/users with create request DTO, service/mapper creation logic, and unit tests.",
                MODE_SPRING_USER_CREATE_RECIPE
        ));
    }

    private static boolean isUserPaginationRequest(AgentTask task) {
        String text = ((task.getTitle() == null ? "" : task.getTitle()) + " "
                + (task.getDescription() == null ? "" : task.getDescription()))
                .toLowerCase(Locale.ROOT);
        boolean userModule = text.contains("user") || text.contains("用户");
        boolean pagination = text.contains("pagination")
                || text.contains("paginated")
                || text.contains("page")
                || text.contains("分页");
        return userModule && pagination;
    }

    private static boolean isUserIdValidationRequest(AgentTask task) {
        String text = ((task.getTitle() == null ? "" : task.getTitle()) + " "
                + (task.getDescription() == null ? "" : task.getDescription()))
                .toLowerCase(Locale.ROOT);
        boolean userModule = text.contains("user") || text.contains("用户");
        boolean idParameter = text.contains(" id")
                || text.contains("id ")
                || text.contains("id")
                || text.contains("编号")
                || text.contains("标识");
        boolean validation = text.contains("validation")
                || text.contains("validate")
                || text.contains("validator")
                || text.contains("校验")
                || text.contains("验证")
                || text.contains("positive")
                || text.contains("negative")
                || text.contains("null")
                || text.contains("负数")
                || text.contains("空值")
                || text.contains("参数");
        boolean fix = text.contains("fix")
                || text.contains("repair")
                || text.contains("bug")
                || text.contains("修复")
                || text.contains("处理");
        return userModule && idParameter && (validation || fix);
    }

    private static boolean isUserCountRequest(AgentTask task) {
        String text = ((task.getTitle() == null ? "" : task.getTitle()) + " "
                + (task.getDescription() == null ? "" : task.getDescription()))
                .toLowerCase(Locale.ROOT);
        boolean userModule = text.contains("user") || text.contains("用户");
        boolean countRequest = text.contains("count")
                || text.contains("total")
                || text.contains("统计")
                || text.contains("数量")
                || text.contains("总数");
        return userModule && countRequest;
    }

    private static boolean isUserCreateRequest(AgentTask task) {
        String text = ((task.getTitle() == null ? "" : task.getTitle()) + " "
                + (task.getDescription() == null ? "" : task.getDescription()))
                .toLowerCase(Locale.ROOT);
        boolean userModule = text.contains("user") || text.contains("用户");
        boolean createRequest = text.contains("create")
                || text.contains("add")
                || text.contains("new")
                || text.contains("post")
                || text.contains("新增")
                || text.contains("创建")
                || text.contains("新建");
        return userModule && createRequest;
    }

    private static boolean replaceFile(
            Path repositoryPath,
            Map<String, String> updatedFiles,
            String relativePath,
            ContentTransformer transformer
    ) {
        Optional<String> content = readString(repositoryPath.resolve(relativePath));
        if (content.isEmpty()) {
            return false;
        }
        Optional<String> updated = transformer.transform(content.get());
        if (updated.isEmpty() || updated.get().equals(content.get())) {
            return false;
        }
        updatedFiles.put(relativePath, updated.get());
        return true;
    }

    private static boolean replaceFileIfChanged(
            Path repositoryPath,
            Map<String, String> updatedFiles,
            String relativePath,
            ContentTransformer transformer
    ) {
        Optional<String> content = readString(repositoryPath.resolve(relativePath));
        if (content.isEmpty()) {
            return false;
        }
        Optional<String> updated = transformer.transform(content.get());
        if (updated.isEmpty()) {
            return false;
        }
        if (!updated.get().equals(content.get())) {
            updatedFiles.put(relativePath, updated.get());
        }
        return true;
    }

    private static Optional<String> readString(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> addTestDependency(String content) {
        if (content.contains("spring-boot-starter-test")) {
            return Optional.of(content);
        }
        String dataJpaDependency = ""
                + "        <dependency>\n"
                + "            <groupId>org.springframework.boot</groupId>\n"
                + "            <artifactId>spring-boot-starter-data-jpa</artifactId>\n"
                + "        </dependency>\n";
        String dataJpaAndTestDependency = dataJpaDependency
                + "        <dependency>\n"
                + "            <groupId>org.springframework.boot</groupId>\n"
                + "            <artifactId>spring-boot-starter-test</artifactId>\n"
                + "            <scope>test</scope>\n"
                + "        </dependency>\n";
        return replaceOnce(content, dataJpaDependency, dataJpaAndTestDependency);
    }

    private static Optional<String> addUserPaginationControllerEndpoint(String content) {
        if (content.contains("listUsersPage(")) {
            return Optional.empty();
        }
        Optional<String> withImport = replaceOnce(content, """
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;
            """, """
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;
            """);
        if (withImport.isEmpty()) {
            return Optional.empty();
        }
        return replaceOnce(withImport.get(), """
                @GetMapping("/{id}")
                public UserEntity getUser(@PathVariable Long id) {
                    return userService.getUser(id);
                }
            """, """
                @GetMapping("/page")
                public List<UserEntity> listUsersPage(
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "10") int size
                ) {
                    return userService.listUsersPage(page, size);
                }

                @GetMapping("/{id}")
                public UserEntity getUser(@PathVariable Long id) {
                    return userService.getUser(id);
                }
            """);
    }

    private static Optional<String> addUserPaginationServiceMethod(String content) {
        if (content.contains("listUsersPage(")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """, """
                public List<UserEntity> listUsersPage(int page, int size) {
                    int safePage = Math.max(page, 0);
                    int safeSize = Math.min(Math.max(size, 1), 100);
                    long offset = (long) safePage * safeSize;
                    if (offset > Integer.MAX_VALUE) {
                        return List.of();
                    }
                    return userMapper.findPage((int) offset, safeSize);
                }

                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """);
    }

    private static Optional<String> addUserPaginationMapperMethod(String content) {
        if (content.contains("findPage(")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public List<UserEntity> findAll() {
                    return List.of(new UserEntity(1L, "Ada Lovelace"));
                }

                public UserEntity findById(Long id) {
                    return new UserEntity(id, "Grace Hopper");
                }
            """, """
                public List<UserEntity> findAll() {
                    return List.of(
                            new UserEntity(1L, "Ada Lovelace"),
                            new UserEntity(2L, "Grace Hopper"),
                            new UserEntity(3L, "Katherine Johnson")
                    );
                }

                public List<UserEntity> findPage(int offset, int size) {
                    List<UserEntity> users = findAll();
                    if (offset >= users.size()) {
                        return List.of();
                    }
                    int toIndex = Math.min(offset + size, users.size());
                    return users.subList(offset, toIndex);
                }

                public UserEntity findById(Long id) {
                    return new UserEntity(id, "Grace Hopper");
                }
            """);
    }

    private static Optional<String> addUserIdValidation(String content) {
        if (content.contains("User id must be positive")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """, """
                public UserEntity getUser(Long id) {
                    if (id == null || id < 1) {
                        throw new IllegalArgumentException("User id must be positive");
                    }
                    return userMapper.findById(id);
                }
            """);
    }

    private static Optional<String> addUserIdValidationTests(String content) {
        if (content.contains("getUserRejectsNonPositiveId")) {
            return Optional.empty();
        }
        String updated = content;
        if (!updated.contains("assertThatThrownBy")) {
            Optional<String> withImport = replaceOnce(updated, """
                import static org.assertj.core.api.Assertions.assertThat;
                """, """
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.assertj.core.api.Assertions.assertThatThrownBy;
                """);
            if (withImport.isEmpty()) {
                return Optional.empty();
            }
            updated = withImport.get();
        }
        int classEnd = updated.lastIndexOf("\n}");
        if (classEnd < 0) {
            return Optional.empty();
        }
        String tests = """

                @Test
                void getUserRejectsMissingId() {
                    assertThatThrownBy(() -> userService.getUser(null))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("User id must be positive");
                }

                @Test
                void getUserRejectsNonPositiveId() {
                    assertThatThrownBy(() -> userService.getUser(0L))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("User id must be positive");
                }
            """;
        return Optional.of(updated.substring(0, classEnd) + tests + updated.substring(classEnd));
    }

    private static Optional<String> addUserCountControllerEndpoint(String content) {
        if (content.contains("countUsers(")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                @GetMapping("/{id}")
                public UserEntity getUser(@PathVariable Long id) {
                    return userService.getUser(id);
                }
            """, """
                @GetMapping("/count")
                public long countUsers() {
                    return userService.countUsers();
                }

                @GetMapping("/{id}")
                public UserEntity getUser(@PathVariable Long id) {
                    return userService.getUser(id);
                }
            """);
    }

    private static Optional<String> addUserCountServiceMethod(String content) {
        if (content.contains("countUsers(")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """, """
                public long countUsers() {
                    return userMapper.countAll();
                }

                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """);
    }

    private static Optional<String> addUserCountMapperMethod(String content) {
        if (content.contains("countAll(")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public UserEntity findById(Long id) {
                    return new UserEntity(id, "Grace Hopper");
                }
            """, """
                public long countAll() {
                    return findAll().size();
                }

                public UserEntity findById(Long id) {
                    return new UserEntity(id, "Grace Hopper");
                }
            """);
    }

    private static Optional<String> addUserCountTests(String content) {
        if (content.contains("countUsersReturnsTotalNumberOfUsers")) {
            return Optional.empty();
        }
        String updated = content;
        if (!updated.contains("import static org.assertj.core.api.Assertions.assertThat;")) {
            Optional<String> withImport = replaceOnce(updated, """
                package com.example.demo.user;

                """, """
                package com.example.demo.user;

                import static org.assertj.core.api.Assertions.assertThat;

                """);
            if (withImport.isEmpty()) {
                return Optional.empty();
            }
            updated = withImport.get();
        }
        int classEnd = updated.lastIndexOf("\n}");
        if (classEnd < 0) {
            return Optional.empty();
        }
        String test = """

                @Test
                void countUsersReturnsTotalNumberOfUsers() {
                    long total = userService.countUsers();

                    assertThat(total).isEqualTo(1);
                }
            """;
        return Optional.of(updated.substring(0, classEnd) + test + updated.substring(classEnd));
    }

    private static Optional<String> addUserCreateControllerEndpoint(String content) {
        if (content.contains("createUser(")) {
            return Optional.empty();
        }
        Optional<String> withImports = replaceOnce(content, """
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestMapping;
            """, """
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            """);
        if (withImports.isEmpty()) {
            return Optional.empty();
        }
        return replaceOnce(withImports.get(), """
                @GetMapping("/{id}")
                public UserEntity getUser(@PathVariable Long id) {
                    return userService.getUser(id);
                }
            """, """
                @PostMapping
                public UserEntity createUser(@RequestBody CreateUserRequest request) {
                    return userService.createUser(request);
                }

                @GetMapping("/{id}")
                public UserEntity getUser(@PathVariable Long id) {
                    return userService.getUser(id);
                }
            """);
    }

    private static Optional<String> addUserCreateServiceMethod(String content) {
        if (content.contains("createUser(")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """, """
                public UserEntity createUser(CreateUserRequest request) {
                    if (request == null || request.getName() == null || request.getName().isBlank()) {
                        throw new IllegalArgumentException("User name must not be blank");
                    }
                    return userMapper.save(request.getName());
                }

                public UserEntity getUser(Long id) {
                    return userMapper.findById(id);
                }
            """);
    }

    private static Optional<String> addUserCreateMapperMethod(String content) {
        if (content.contains("save(String name)")) {
            return Optional.empty();
        }
        return replaceOnce(content, """
                public UserEntity findById(Long id) {
                    return new UserEntity(id, "Grace Hopper");
                }
            """, """
                public UserEntity save(String name) {
                    return new UserEntity((long) findAll().size() + 1, name);
                }

                public UserEntity findById(Long id) {
                    return new UserEntity(id, "Grace Hopper");
                }
            """);
    }

    private static Optional<String> addUserCreateTests(String content) {
        if (content.contains("createUserReturnsCreatedUser")) {
            return Optional.empty();
        }
        String updated = content;
        if (!updated.contains("assertThat")) {
            Optional<String> withImport = replaceOnce(updated, """
                package com.example.demo.user;

                """, """
                package com.example.demo.user;

                import static org.assertj.core.api.Assertions.assertThat;

                """);
            if (withImport.isEmpty()) {
                return Optional.empty();
            }
            updated = withImport.get();
        }
        if (!updated.contains("import static org.assertj.core.api.Assertions.assertThatThrownBy;")) {
            Optional<String> withImport = replaceOnce(updated, """
                import static org.assertj.core.api.Assertions.assertThat;
                """, """
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.assertj.core.api.Assertions.assertThatThrownBy;
                """);
            if (withImport.isEmpty()) {
                return Optional.empty();
            }
            updated = withImport.get();
        }
        int classEnd = updated.lastIndexOf("\n}");
        if (classEnd < 0) {
            return Optional.empty();
        }
        String tests = """

                @Test
                void createUserReturnsCreatedUser() {
                    UserEntity user = userService.createUser(new CreateUserRequest("Margaret Hamilton"));

                    assertThat(user.getId()).isPositive();
                    assertThat(user.getName()).isEqualTo("Margaret Hamilton");
                }

                @Test
                void createUserRejectsBlankName() {
                    assertThatThrownBy(() -> userService.createUser(new CreateUserRequest(" ")))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("User name must not be blank");
                }
            """;
        return Optional.of(updated.substring(0, classEnd) + tests + updated.substring(classEnd));
    }

    private static Optional<String> replaceOnce(String content, String current, String replacement) {
        int index = content.indexOf(current);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(content.substring(0, index) + replacement + content.substring(index + current.length()));
    }

    private List<String> planLines(AgentTask task, AgentRun run, List<CodeSearchResultResponse> retrievedResults) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("# RepoPilot Task " + task.getId() + " Coder Plan");
        lines.add("");
        lines.add("- Run: " + run.getId());
        lines.add("- Type: " + task.getTaskType());
        lines.add("- Title: " + task.getTitle());
        lines.add("- Project: " + task.getProject().getRepoFullName());
        lines.add("- Generation mode: `" + MODE_SAFE_PLANNING_FALLBACK + "`");
        lines.add("- Retrieved chunks: " + retrievedResults.size());
        lines.add("");
        lines.add("## Request");
        lines.add("");
        lines.add(task.getDescription() == null ? "" : task.getDescription());
        lines.add("");
        lines.add("## Why No Code Was Changed");
        lines.add("");
        lines.add("No supported Spring Coder recipe matched this task, so RepoPilot generated a retrieval-grounded plan instead of editing source files. This keeps the run reviewable while preserving the context needed by a future LLM-backed CoderAgent.");
        lines.add("");
        lines.add("## Candidate Files From Retrieval");
        lines.add("");
        if (retrievedResults.isEmpty()) {
            lines.add("- No indexed chunks were retrieved.");
        } else {
            lines.add("| File | Chunk | Symbol | Lines | Context |");
            lines.add("| --- | --- | --- | --- | --- |");
            for (CodeSearchResultResponse result : retrievedResults.stream().limit(FALLBACK_CONTEXT_LIMIT).toList()) {
                lines.add("| `" + markdownCell(result.filePath()) + "` | "
                        + markdownCell(result.chunkType() == null ? null : result.chunkType().name()) + " | "
                        + markdownCell(symbolName(result)) + " | "
                        + markdownCell(lineSpan(result.startLine(), result.endLine())) + " | "
                        + markdownCell(contextSummary(result)) + " |");
            }
        }
        lines.add("");
        lines.add("## Suggested Edit Sequence");
        lines.add("");
        if (retrievedResults.isEmpty()) {
            lines.add("1. Re-run indexing or refine the task title/description so RetrieverAgent can find the target Controller, Service, Mapper, Entity, or test files.");
        } else {
            int order = 1;
            for (CodeSearchResultResponse result : retrievedResults.stream().limit(5).toList()) {
                lines.add(order + ". Inspect `" + result.filePath() + "`"
                        + suggestedContextSuffix(result)
                        + " before drafting code changes.");
                order++;
            }
            lines.add(order + ". Draft the smallest unified diff that satisfies the request and updates or adds focused tests.");
        }
        lines.add("");
        lines.add("## Guardrails For The Next Coder Pass");
        lines.add("");
        lines.add("- Prefer existing Controller, Service, Mapper, Entity, and test patterns from the retrieved files.");
        lines.add("- Keep edits scoped to the requested behavior and avoid unrelated refactors.");
        lines.add("- Add or update tests for the changed behavior before asking SandboxAgent to run Maven.");
        lines.add("- If the task requires a new endpoint, review authentication, validation, and request/response DTO risks.");
        lines.add("");
        lines.add("## Required Validation");
        lines.add("");
        lines.add("- Apply the generated diff in the sandbox.");
        lines.add("- Run `mvn -q test`.");
        lines.add("- Run PatchRiskReview before human approval.");
        return lines;
    }

    private String lineRange(Integer startLine, Integer endLine) {
        if (startLine == null || endLine == null) {
            return "";
        }
        return " lines " + startLine + "-" + endLine;
    }

    private String lineSpan(Integer startLine, Integer endLine) {
        if (startLine == null || endLine == null) {
            return "-";
        }
        return startLine + "-" + endLine;
    }

    private String symbolName(CodeSearchResultResponse result) {
        if (result.qualifiedName() != null && !result.qualifiedName().isBlank()) {
            return result.qualifiedName();
        }
        if (result.symbolName() != null && !result.symbolName().isBlank()) {
            return result.symbolName();
        }
        return "file";
    }

    private String contextSummary(CodeSearchResultResponse result) {
        if (result.summary() != null && !result.summary().isBlank()) {
            return result.summary();
        }
        return result.preview();
    }

    private String suggestedContextSuffix(CodeSearchResultResponse result) {
        String symbol = symbolName(result);
        String lineSpan = lineSpan(result.startLine(), result.endLine());
        if ("file".equals(symbol) && "-".equals(lineSpan)) {
            return "";
        }
        if ("file".equals(symbol)) {
            return " around lines " + lineSpan;
        }
        if ("-".equals(lineSpan)) {
            return " for `" + symbol + "`";
        }
        return " for `" + symbol + "` around lines " + lineSpan;
    }

    private String markdownCell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String compact = value.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        String truncated = compact.length() <= FALLBACK_PREVIEW_LIMIT
                ? compact
                : compact.substring(0, FALLBACK_PREVIEW_LIMIT) + "...";
        return truncated.replace("|", "\\|");
    }

    private static String newFileDiff(String filePath, List<String> lines) {
        StringBuilder builder = new StringBuilder();
        builder.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        builder.append("new file mode 100644\n");
        builder.append("index 0000000..1111111\n");
        builder.append("--- /dev/null\n");
        builder.append("+++ b/").append(filePath).append("\n");
        builder.append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
        for (String line : lines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString();
    }

    private static String replaceFileDiff(String filePath, String oldContent, String newContent) {
        List<String> oldLines = lines(oldContent);
        List<String> newLines = lines(newContent);
        StringBuilder builder = new StringBuilder();
        builder.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        builder.append("index 1111111..2222222 100644\n");
        builder.append("--- a/").append(filePath).append("\n");
        builder.append("+++ b/").append(filePath).append("\n");
        builder.append("@@ -1,").append(oldLines.size()).append(" +1,").append(newLines.size()).append(" @@\n");
        for (String line : oldLines) {
            builder.append("-").append(line).append("\n");
        }
        for (String line : newLines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString();
    }

    private static List<String> lines(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
    }

    private record PatchRecipe(String name, PatchRecipeGenerator generator) {
    }

    private record GeneratedPatch(String diff, String summary, String generationMode) {
    }

    @FunctionalInterface
    private interface PatchRecipeGenerator {
        Optional<GeneratedPatch> generate(AgentTask task);
    }

    private interface ContentTransformer {
        Optional<String> transform(String content);
    }

    private static final String USER_SERVICE_TEST = """
            package com.example.demo.user;

            import static org.assertj.core.api.Assertions.assertThat;

            import java.util.List;

            import org.junit.jupiter.api.Test;

            class UserServiceTest {

                private final UserService userService = new UserService(new UserMapper());

                @Test
                void listUsersPageReturnsRequestedSlice() {
                    List<UserEntity> users = userService.listUsersPage(0, 2);

                    assertThat(users)
                            .extracting(UserEntity::getName)
                            .containsExactly("Ada Lovelace", "Grace Hopper");
                }

                @Test
                void listUsersPageNormalizesBounds() {
                    List<UserEntity> users = userService.listUsersPage(-1, 1000);

                    assertThat(users).hasSize(3);
                }

                @Test
                void listUsersPageReturnsEmptyPageWhenOffsetIsPastEnd() {
                    List<UserEntity> users = userService.listUsersPage(10, 10);

                    assertThat(users).isEmpty();
                }
            }
            """;

    private static final String USER_ID_VALIDATION_TEST = """
            package com.example.demo.user;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            import org.junit.jupiter.api.Test;

            class UserServiceTest {

                private final UserService userService = new UserService(new UserMapper());

                @Test
                void getUserReturnsRequestedId() {
                    UserEntity user = userService.getUser(1L);

                    assertThat(user.getId()).isEqualTo(1L);
                }

                @Test
                void getUserRejectsMissingId() {
                    assertThatThrownBy(() -> userService.getUser(null))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("User id must be positive");
                }

                @Test
                void getUserRejectsNonPositiveId() {
                    assertThatThrownBy(() -> userService.getUser(0L))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("User id must be positive");
                }
            }
            """;

    private static final String USER_COUNT_TEST = """
            package com.example.demo.user;

            import static org.assertj.core.api.Assertions.assertThat;

            import org.junit.jupiter.api.Test;

            class UserServiceTest {

                private final UserService userService = new UserService(new UserMapper());

                @Test
                void countUsersReturnsTotalNumberOfUsers() {
                    long total = userService.countUsers();

                    assertThat(total).isEqualTo(1);
                }
            }
            """;

    private static final String CREATE_USER_REQUEST = """
            package com.example.demo.user;

            public class CreateUserRequest {

                private String name;

                public CreateUserRequest() {
                }

                public CreateUserRequest(String name) {
                    this.name = name;
                }

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }
            }
            """;

    private static final String USER_CREATE_TEST = """
            package com.example.demo.user;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            import org.junit.jupiter.api.Test;

            class UserServiceTest {

                private final UserService userService = new UserService(new UserMapper());

                @Test
                void createUserReturnsCreatedUser() {
                    UserEntity user = userService.createUser(new CreateUserRequest("Margaret Hamilton"));

                    assertThat(user.getId()).isPositive();
                    assertThat(user.getName()).isEqualTo("Margaret Hamilton");
                }

                @Test
                void createUserRejectsBlankName() {
                    assertThatThrownBy(() -> userService.createUser(new CreateUserRequest(" ")))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("User name must not be blank");
                }
            }
            """;
}
