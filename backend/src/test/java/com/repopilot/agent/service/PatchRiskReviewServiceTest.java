package com.repopilot.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.project.domain.Project;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.user.domain.User;
import org.junit.jupiter.api.Test;

class PatchRiskReviewServiceTest {

    private final PatchRiskReviewService service = new PatchRiskReviewService();

    @Test
    void reviewFlagsEndpointAuthBoundsAndTestCoverageForDemoPatch() {
        PatchRecord patch = patch("""
                diff --git a/src/main/java/com/example/demo/user/UserController.java b/src/main/java/com/example/demo/user/UserController.java
                --- a/src/main/java/com/example/demo/user/UserController.java
                +++ b/src/main/java/com/example/demo/user/UserController.java
                @@ -1,2 +1,8 @@
                +    @GetMapping("/page")
                +    public List<UserEntity> listUsersPage(
                +            @RequestParam(defaultValue = "0") int page,
                +            @RequestParam(defaultValue = "10") int size
                +    ) {
                +        return userService.listUsersPage(page, size);
                +    }
                diff --git a/src/main/java/com/example/demo/user/UserService.java b/src/main/java/com/example/demo/user/UserService.java
                --- a/src/main/java/com/example/demo/user/UserService.java
                +++ b/src/main/java/com/example/demo/user/UserService.java
                @@ -1,2 +1,5 @@
                +    int safePage = Math.max(page, 0);
                +    int safeSize = Math.min(Math.max(size, 1), 100);
                +    return userMapper.findPage((int) offset, safeSize);
                diff --git a/src/test/java/com/example/demo/user/UserServiceTest.java b/src/test/java/com/example/demo/user/UserServiceTest.java
                --- /dev/null
                +++ b/src/test/java/com/example/demo/user/UserServiceTest.java
                @@ -0,0 +1,2 @@
                +class UserServiceTest {
                +}
                """);

        PatchRiskReviewService.PatchRiskReview review = service.review(patch, passedTestRun(patch));

        assertThat(review.riskLevel()).isEqualTo("MEDIUM");
        assertThat(review.summary()).contains("highest risk is MEDIUM");
        assertThat(review.findings())
                .extracting(PatchRiskReviewService.PatchRiskFinding::code)
                .contains(
                        "NEW_CONTROLLER_ENDPOINT_WITHOUT_AUTH",
                        "PAGINATION_BOUNDS_NORMALIZED",
                        "TEST_COVERAGE_PRESENT"
                );
    }

    @Test
    void reviewFlagsProductionChangesWithoutTests() {
        PatchRecord patch = patch("""
                diff --git a/src/main/java/com/example/demo/user/UserService.java b/src/main/java/com/example/demo/user/UserService.java
                --- a/src/main/java/com/example/demo/user/UserService.java
                +++ b/src/main/java/com/example/demo/user/UserService.java
                @@ -1 +1,2 @@
                +    public void changeProductionCode() {}
                """);

        PatchRiskReviewService.PatchRiskReview review = service.review(patch, passedTestRun(patch));

        assertThat(review.riskLevel()).isEqualTo("MEDIUM");
        assertThat(review.findings())
                .extracting(PatchRiskReviewService.PatchRiskFinding::code)
                .contains("TEST_COVERAGE_MISSING");
    }

    private PatchRecord patch(String diff) {
        User user = new User("review@example.test", "hash", "Review", "USER");
        Project project = new Project(user, "file:///demo", "demo", "main");
        AgentTask task = new AgentTask(project, user, AgentTaskType.FEATURE, "Review patch", "Review patch");
        AgentRun run = new AgentRun(task);
        return new PatchRecord(task, run, "main", "repopilot/task-1", diff, "Patch under review");
    }

    private TestRun passedTestRun(PatchRecord patch) {
        return new TestRun(
                patch.getAgentRun(),
                patch,
                "mvn -q test",
                0,
                100,
                "",
                TestRunStatus.PASSED
        );
    }
}
