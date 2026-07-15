package com.repopilot.pullrequest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.agent.service.ProjectWriteGuardService;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.pullrequest.domain.PullRequestProvider;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.pullrequest.dto.PullRequestPreflightCheckResponse;
import com.repopilot.pullrequest.dto.PullRequestPreflightResponse;
import com.repopilot.pullrequest.repository.PullRequestRecordRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PullRequestService {

    private static final int MAX_TITLE_LENGTH = 255;

    private final PullRequestRecordRepository pullRequestRecordRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final PatchRecordRepository patchRecordRepository;
    private final TestRunRepository testRunRepository;
    private final PullRequestGitService pullRequestGitService;
    private final GitHubPullRequestService gitHubPullRequestService;
    private final ProjectWriteGuardService projectWriteGuardService;

    public PullRequestService(
            PullRequestRecordRepository pullRequestRecordRepository,
            AgentTaskRepository agentTaskRepository,
            PatchRecordRepository patchRecordRepository,
            TestRunRepository testRunRepository,
            PullRequestGitService pullRequestGitService,
            GitHubPullRequestService gitHubPullRequestService,
            ProjectWriteGuardService projectWriteGuardService
    ) {
        this.pullRequestRecordRepository = pullRequestRecordRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.patchRecordRepository = patchRecordRepository;
        this.testRunRepository = testRunRepository;
        this.pullRequestGitService = pullRequestGitService;
        this.gitHubPullRequestService = gitHubPullRequestService;
        this.projectWriteGuardService = projectWriteGuardService;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PullRequestRecord prepare(Long taskId, Long userId) {
        AgentTask task = task(taskId);
        ensureOwner(task, userId);
        if (task.getStatus() == AgentTaskStatus.DONE) {
            return pullRequestRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(taskId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "Task is not ready to create a pull request"));
        }
        ensurePrCreationState(task);
        projectWriteGuardService.ensureProjectWriteSlot(
                task,
                Set.of(AgentTaskStatus.CREATING_PULL_REQUEST, AgentTaskStatus.FAILED_PR_CREATION),
                "Task is not ready to create pull request"
        );

        PatchRecord patch = patchRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PATCH_NOT_FOUND", "No patch exists for task"));
        ensureApproved(patch);
        ensureTestPassed(patch);

        PullRequestRecord record = pullRequestRecordRepository.findFirstByPatchIdOrderByCreatedAtDesc(patch.getId())
                .orElseGet(() -> createRecord(task, patch));
        return publishIfEnabled(record);
    }

    @Transactional(readOnly = true)
    public PullRequestRecord latest(Long taskId, Long userId) {
        AgentTask task = task(taskId);
        ensureOwner(task, userId);
        return pullRequestRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PULL_REQUEST_NOT_FOUND", "Pull request record not found"));
    }

    @Transactional(readOnly = true)
    public PullRequestPreflightResponse preflight(Long taskId, Long userId) {
        AgentTask task = task(taskId);
        ensureOwner(task, userId);
        Optional<PatchRecord> latestPatch = patchRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(taskId);
        Optional<TestRun> latestTestRun = latestPatch.flatMap(patch ->
                testRunRepository.findFirstByPatchIdOrderByCreatedAtDesc(patch.getId())
        );
        Optional<PullRequestRecord> latestRecord = pullRequestRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(taskId);

        boolean taskReady = task.getStatus() == AgentTaskStatus.CREATING_PULL_REQUEST
                || task.getStatus() == AgentTaskStatus.FAILED_PR_CREATION;
        boolean alreadyPrepared = task.getStatus() == AgentTaskStatus.DONE && latestRecord.isPresent();
        boolean patchApproved = latestPatch
                .map(patch -> patch.getStatus() == PatchStatus.APPROVED)
                .orElse(false);
        boolean testPassed = latestTestRun
                .map(testRun -> testRun.getStatus() == TestRunStatus.PASSED)
                .orElse(false);
        boolean localDraftReady = taskReady && patchApproved && testPassed;
        boolean remotePublishingEnabled = gitHubPullRequestService.isRemotePublishingEnabled();
        boolean repositoryEligible = gitHubPullRequestService.isRepositoryEligible(task.getProject());
        boolean tokenConfigured = gitHubPullRequestService.isTokenConfigured();
        boolean remotePublishingWillRun = remotePublishingEnabled && repositoryEligible;
        boolean remoteReady = !remotePublishingWillRun || tokenConfigured;
        boolean canPrepare = localDraftReady && remoteReady;

        List<PullRequestPreflightCheckResponse> checks = new ArrayList<>();
        checks.add(taskStatusCheck(task, taskReady, alreadyPrepared));
        checks.add(patchCheck(latestPatch));
        checks.add(testCheck(latestPatch, latestTestRun));
        checks.add(localDraftCheck(localDraftReady, alreadyPrepared));
        checks.add(remoteGitHubCheck(remotePublishingEnabled, repositoryEligible, tokenConfigured));

        List<String> blockers = checks.stream()
                .filter(check -> check.status().equals("BLOCKED") || check.status().equals("PENDING"))
                .map(PullRequestPreflightCheckResponse::message)
                .toList();

        return new PullRequestPreflightResponse(
                task.getId(),
                task.getStatus(),
                canPrepare,
                remotePublishingWillRun ? "REMOTE_GITHUB_PR" : "LOCAL_DRAFT_ONLY",
                localDraftReady || alreadyPrepared,
                remotePublishingEnabled,
                remotePublishingWillRun,
                remoteReady,
                repositoryEligible,
                tokenConfigured,
                latestPatch.map(PatchRecord::getStatus).orElse(null),
                latestTestRun.map(TestRun::getStatus).orElse(null),
                latestRecord.map(PullRequestRecord::getStatus).orElse(null),
                checks,
                blockers
        );
    }

    private AgentTask task(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found"));
    }

    private void ensureOwner(AgentTask task, Long userId) {
        if (!task.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_TASK_FORBIDDEN", "Task does not belong to current user");
        }
    }

    private void ensurePrCreationState(AgentTask task) {
        if (task.getStatus() == AgentTaskStatus.WAITING_HUMAN_APPROVAL) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "Patch must be approved before preparing a pull request");
        }
        if (task.getStatus() != AgentTaskStatus.CREATING_PULL_REQUEST
                && task.getStatus() != AgentTaskStatus.FAILED_PR_CREATION) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "Task is not ready to create a pull request");
        }
    }

    private PullRequestPreflightCheckResponse taskStatusCheck(AgentTask task, boolean taskReady, boolean alreadyPrepared) {
        if (taskReady) {
            return check("TASK_STATUS", "Task state", "PASS", "Task is ready to prepare a pull request.");
        }
        if (alreadyPrepared) {
            return check("TASK_STATUS", "Task state", "PASS", "Pull request record has already been prepared.");
        }
        if (task.getStatus() == AgentTaskStatus.WAITING_HUMAN_APPROVAL) {
            return check("TASK_STATUS", "Task state", "BLOCKED", "Approve the tested patch before preparing a pull request.");
        }
        return check("TASK_STATUS", "Task state", "BLOCKED", "Task status " + task.getStatus() + " is not ready for pull request preparation.");
    }

    private PullRequestPreflightCheckResponse patchCheck(Optional<PatchRecord> latestPatch) {
        if (latestPatch.isEmpty()) {
            return check("PATCH_APPROVED", "Patch approval", "PENDING", "No patch has been generated yet.");
        }
        PatchRecord patch = latestPatch.get();
        if (patch.getStatus() == PatchStatus.APPROVED) {
            return check("PATCH_APPROVED", "Patch approval", "PASS", "Latest patch is approved.");
        }
        return check("PATCH_APPROVED", "Patch approval", "BLOCKED", "Latest patch status is " + patch.getStatus() + "; approval is required.");
    }

    private PullRequestPreflightCheckResponse testCheck(Optional<PatchRecord> latestPatch, Optional<TestRun> latestTestRun) {
        if (latestPatch.isEmpty()) {
            return check("SANDBOX_TEST", "Sandbox test", "PENDING", "Sandbox test waits for a generated patch.");
        }
        if (latestTestRun.isEmpty()) {
            return check("SANDBOX_TEST", "Sandbox test", "PENDING", "Latest patch has no sandbox test run.");
        }
        TestRun testRun = latestTestRun.get();
        if (testRun.getStatus() == TestRunStatus.PASSED) {
            return check("SANDBOX_TEST", "Sandbox test", "PASS", "Latest sandbox test passed.");
        }
        return check("SANDBOX_TEST", "Sandbox test", "BLOCKED", "Latest sandbox test status is " + testRun.getStatus() + "; passing tests are required.");
    }

    private PullRequestPreflightCheckResponse localDraftCheck(boolean localDraftReady, boolean alreadyPrepared) {
        if (alreadyPrepared) {
            return check("LOCAL_DRAFT", "Local draft", "PASS", "Local branch and commit are already recorded.");
        }
        if (localDraftReady) {
            return check("LOCAL_DRAFT", "Local draft", "PASS", "Local branch and commit can be prepared.");
        }
        return check("LOCAL_DRAFT", "Local draft", "PENDING", "Local draft waits for task readiness, approval, and a passed sandbox test.");
    }

    private PullRequestPreflightCheckResponse remoteGitHubCheck(boolean remotePublishingEnabled, boolean repositoryEligible, boolean tokenConfigured) {
        if (!remotePublishingEnabled) {
            return check("REMOTE_GITHUB", "Remote GitHub", "WARN", "Remote GitHub publishing is disabled; RepoPilot will stop at DRAFT_READY.");
        }
        if (!repositoryEligible) {
            return check("REMOTE_GITHUB", "Remote GitHub", "WARN", "Project is not a github.com repository; remote PR creation will be skipped.");
        }
        if (!tokenConfigured) {
            return check("REMOTE_GITHUB", "Remote GitHub", "BLOCKED", "GitHub remote publishing is enabled for this repository, but no token is configured.");
        }
        return check("REMOTE_GITHUB", "Remote GitHub", "PASS", "Remote GitHub PR creation is configured.");
    }

    private PullRequestPreflightCheckResponse check(String code, String label, String status, String message) {
        return new PullRequestPreflightCheckResponse(code, label, status, message);
    }

    private void ensureApproved(PatchRecord patch) {
        if (patch.getStatus() != PatchStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "Latest patch must be approved before preparing a pull request");
        }
    }

    private void ensureTestPassed(PatchRecord patch) {
        TestRun testRun = testRunRepository.findFirstByPatchIdOrderByCreatedAtDesc(patch.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PATCH_TEST_NOT_FOUND", "Patch must have a sandbox test run before preparing a pull request"));
        if (testRun.getStatus() != TestRunStatus.PASSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_TEST_NOT_PASSED", "Latest sandbox test run must pass before preparing a pull request");
        }
    }

    private PullRequestRecord createRecord(AgentTask task, PatchRecord patch) {
        String title = title(task);
        PullRequestGitService.MaterializedPullRequest materialized = pullRequestGitService.materialize(task, patch, title);
        return pullRequestRecordRepository.save(new PullRequestRecord(
                task,
                patch,
                PullRequestProvider.GITHUB,
                title,
                body(task, patch, materialized),
                materialized.baseBranch(),
                materialized.targetBranch(),
                materialized.commitSha(),
                materialized.commitMessage(),
                PullRequestStatus.DRAFT_READY
        ));
    }

    private PullRequestRecord publishIfEnabled(PullRequestRecord record) {
        if (record.getStatus() == PullRequestStatus.OPEN) {
            markTaskDone(record.getAgentTask());
            return record;
        }
        if (!gitHubPullRequestService.shouldPublish(record.getAgentTask().getProject())) {
            markTaskDone(record.getAgentTask());
            return record;
        }
        try {
            GitHubPullRequestService.GitHubPullRequest pullRequest = gitHubPullRequestService.publish(
                    record.getAgentTask().getProject(),
                    record
            );
            record.markOpen(pullRequest.number(), pullRequest.url());
            record.getAgentTask().setStatus(AgentTaskStatus.DONE);
            agentTaskRepository.save(record.getAgentTask());
            return pullRequestRecordRepository.save(record);
        } catch (ApiException exception) {
            record.markFailed(exception.getMessage());
            record.getAgentTask().setStatus(AgentTaskStatus.FAILED_PR_CREATION);
            agentTaskRepository.save(record.getAgentTask());
            pullRequestRecordRepository.save(record);
            throw exception;
        }
    }

    private void markTaskDone(AgentTask task) {
        if (task.getStatus() != AgentTaskStatus.DONE) {
            task.setStatus(AgentTaskStatus.DONE);
            agentTaskRepository.save(task);
        }
    }

    private String title(AgentTask task) {
        String title = "RepoPilot: " + task.getTitle();
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        return title.substring(0, MAX_TITLE_LENGTH);
    }

    private String body(AgentTask task, PatchRecord patch, PullRequestGitService.MaterializedPullRequest materialized) {
        return """
                Prepared by RepoPilot.

                Task:
                %s

                Patch:
                - Patch ID: %d
                - Base branch: %s
                - Target branch: %s
                - Status: %s

                Local Git:
                - Commit: %s

                Notes:
                GitHub API creation is not enabled in this local slice yet, so the branch and commit are prepared locally without an external PR URL.
                """
                .formatted(
                        task.getDescription(),
                        patch.getId(),
                        materialized.baseBranch(),
                        materialized.targetBranch(),
                        patch.getStatus(),
                        materialized.commitSha()
                );
    }
}
