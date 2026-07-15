package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.project.domain.Project;
import com.repopilot.user.domain.User;
import org.junit.jupiter.api.Test;

class PatchDiffSafetyServiceTest {

    private final PatchDiffSafetyService service = new PatchDiffSafetyService();

    @Test
    void reviewAllowsProjectRelativeSourceAndPlanPaths() {
        PatchDiffSafetyService.PatchDiffSafetyReport report = service.review(patch("""
                diff --git a/src/main/java/com/example/demo/UserService.java b/src/main/java/com/example/demo/UserService.java
                --- a/src/main/java/com/example/demo/UserService.java
                +++ b/src/main/java/com/example/demo/UserService.java
                @@ -1 +1,2 @@
                +class UserService {}
                diff --git a/.repopilot/task-1-plan.md b/.repopilot/task-1-plan.md
                new file mode 100644
                --- /dev/null
                +++ b/.repopilot/task-1-plan.md
                @@ -0,0 +1 @@
                +plan
                """));

        assertThat(report.safe()).isTrue();
        assertThat(report.changedPaths()).containsExactly(
                "src/main/java/com/example/demo/UserService.java",
                ".repopilot/task-1-plan.md"
        );
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void reviewRejectsTraversalReservedPathsAndBinaryPatches() {
        PatchDiffSafetyService.PatchDiffSafetyReport report = service.review(patch("""
                diff --git a/../outside.txt b/../outside.txt
                --- a/../outside.txt
                +++ b/../outside.txt
                @@ -1 +1 @@
                +outside
                diff --git a/.git/config b/.git/config
                --- a/.git/config
                +++ b/.git/config
                @@ -1 +1 @@
                +unsafe
                GIT binary patch
                """));

        assertThat(report.safe()).isFalse();
        assertThat(report.findings())
                .extracting(PatchDiffSafetyService.PatchDiffSafetyFinding::code)
                .contains("UNSAFE_PATCH_PATH", "RESERVED_PATCH_PATH", "BINARY_PATCH_UNSUPPORTED");
    }

    @Test
    void reviewRejectsMissingDiffHeader() {
        PatchDiffSafetyService.PatchDiffSafetyReport report = service.review(patch("""
                @@ -1 +1 @@
                +not a full diff
                """));

        assertThat(report.safe()).isFalse();
        assertThat(report.findings())
                .extracting(PatchDiffSafetyService.PatchDiffSafetyFinding::code)
                .contains("DIFF_HEADER_MISSING");
    }

    private PatchRecord patch(String diff) {
        User user = new User("safety@example.test", "hash", "Safety", "USER");
        Project project = new Project(user, "file:///demo", "demo", "main");
        AgentTask task = new AgentTask(project, user, AgentTaskType.FEATURE, "Safety", "Safety");
        AgentRun run = new AgentRun(task);
        return new PatchRecord(task, run, "main", "repopilot/task-1", diff, "Safety patch");
    }
}
