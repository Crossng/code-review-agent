package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchRepairServiceTest {

    private static final Path DEMO_REPOSITORY = Path.of("..", "examples", "demo-spring-repo").normalize();

    @Mock
    private PatchRecordRepository patchRecordRepository;

    private PatchRepairService patchRepairService;

    @BeforeEach
    void setUp() {
        patchRepairService = new PatchRepairService(patchRecordRepository);
    }

    @Test
    void repairsMissingSpringBootTestDependency(@TempDir Path repositoryPath) throws IOException {
        when(patchRecordRepository.save(any(PatchRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Fixture fixture = fixture(repositoryPath);

        PatchRecord repaired = patchRepairService.repairMissingTestDependency(
                fixture.task(),
                fixture.run(),
                fixture.patch(),
                failedTestRun(fixture, "package org.junit.jupiter.api does not exist"),
                1
        );

        assertThat(repaired.getSummary()).contains("修复尝试 1");
        assertThat(repaired.getGenerationMode()).isEqualTo("REPAIR_MISSING_TEST_DEPENDENCY");
        assertThat(repaired.getDiffContent())
                .startsWith("diff --git a/pom.xml b/pom.xml")
                .contains("spring-boot-starter-test")
                .contains("diff --git a/src/test/java/com/example/demo/user/UserServiceTest.java");
    }

    @Test
    void repairsMissingJavaImportFromMavenCompileLog(@TempDir Path repositoryPath) throws IOException, InterruptedException {
        when(patchRecordRepository.save(any(PatchRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        String filePath = "src/main/java/com/example/demo/user/UserService.java";
        String original = Files.readString(repositoryPath.resolve(filePath));
        String changed = original.replace(
                "    public UserEntity getUser(Long id) {\n        return userMapper.findById(id);\n    }\n",
                "    public UserEntity getUser(Long id) {\n        Objects.requireNonNull(id, \"id\");\n        return userMapper.findById(id);\n    }\n"
        );
        Fixture fixture = fixture(repositoryPath, replaceFileDiff(filePath, original, changed));

        PatchRecord repaired = patchRepairService.repairMavenFailure(
                fixture.task(),
                fixture.run(),
                fixture.patch(),
                failedTestRun(fixture, """
                        [ERROR] %s:[18,9] cannot find symbol
                        [ERROR]   symbol:   variable Objects
                        [ERROR]   location: class com.example.demo.user.UserService
                        """.formatted(repositoryPath.resolve(filePath))),
                1
        );

        assertThat(repaired.getGenerationMode()).isEqualTo("REPAIR_MISSING_JAVA_IMPORT");
        assertThat(repaired.getSummary()).contains("补充 java.util.Objects import");
        assertThat(repaired.getDiffContent())
                .startsWith("diff --git a/src/main/java/com/example/demo/user/UserService.java b/src/main/java/com/example/demo/user/UserService.java")
                .contains("+import java.util.Objects;")
                .contains("+        Objects.requireNonNull(id, \"id\");");
        assertGitApplyChecks(repositoryPath, repaired.getDiffContent());
    }

    @Test
    void rejectsUnrecognizedFailure(@TempDir Path repositoryPath) throws IOException {
        copyDirectory(DEMO_REPOSITORY, repositoryPath);
        Fixture fixture = fixture(repositoryPath);

        assertThatThrownBy(() -> patchRepairService.repairMissingTestDependency(
                fixture.task(),
                fixture.run(),
                fixture.patch(),
                failedTestRun(fixture, "expected true but was false"),
                1
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("确定性修复");
    }

    private Fixture fixture(Path repositoryPath) {
        return fixture(repositoryPath, userServiceTestOnlyDiff());
    }

    private Fixture fixture(Path repositoryPath, String diffContent) {
        User user = new User("repair@example.test", "hash", "Repair", "USER");
        Project project = new Project(user, "file://demo-spring-repo", "demo-spring-repo", "main");
        project.setLocalPath(repositoryPath.toString());
        AgentTask task = new AgentTask(
                project,
                user,
                AgentTaskType.BUGFIX,
                "Fix User id validation bug",
                "修复 User id 参数校验。"
        );
        AgentRun run = new AgentRun(task);
        PatchRecord patch = new PatchRecord(
                task,
                run,
                "main",
                "repopilot/task-1",
                diffContent,
                "Broken patch without test dependency"
        );
        return new Fixture(task, run, patch);
    }

    private TestRun failedTestRun(Fixture fixture, String logExcerpt) {
        return new TestRun(
                fixture.run(),
                fixture.patch(),
                "mvn -q test",
                1,
                100,
                logExcerpt,
                TestRunStatus.FAILED
        );
    }

    private String userServiceTestOnlyDiff() {
        return """
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
                """;
    }

    private static String replaceFileDiff(String filePath, String oldContent, String newContent) {
        String[] oldLines = oldContent.replace("\r\n", "\n").replaceFirst("\\n$", "").split("\n", -1);
        String[] newLines = newContent.replace("\r\n", "\n").replaceFirst("\\n$", "").split("\n", -1);
        StringBuilder builder = new StringBuilder();
        builder.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        builder.append("index 1111111..2222222 100644\n");
        builder.append("--- a/").append(filePath).append("\n");
        builder.append("+++ b/").append(filePath).append("\n");
        builder.append("@@ -1,").append(oldLines.length).append(" +1,").append(newLines.length).append(" @@\n");
        for (String line : oldLines) {
            builder.append("-").append(line).append("\n");
        }
        for (String line : newLines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString();
    }

    private static void assertGitApplyChecks(Path repositoryPath, String diffContent) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "apply", "--check", "-")
                .directory(repositoryPath.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(diffContent.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertThat(exitCode)
                .as(output)
                .isZero();
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

    private record Fixture(AgentTask task, AgentRun run, PatchRecord patch) {
    }
}
