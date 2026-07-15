package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
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

        assertThat(repaired.getSummary()).contains("Repair attempt 1");
        assertThat(repaired.getDiffContent())
                .startsWith("diff --git a/pom.xml b/pom.xml")
                .contains("spring-boot-starter-test")
                .contains("diff --git a/src/test/java/com/example/demo/user/UserServiceTest.java");
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
                .hasMessageContaining("no deterministic repair");
    }

    private Fixture fixture(Path repositoryPath) {
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
                userServiceTestOnlyDiff(),
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
