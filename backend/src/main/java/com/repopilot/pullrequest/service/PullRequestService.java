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
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "任务还不能准备 PR"));
        }
        ensurePrCreationState(task);
        projectWriteGuardService.ensureProjectWriteSlot(
                task,
                Set.of(AgentTaskStatus.CREATING_PULL_REQUEST, AgentTaskStatus.FAILED_PR_CREATION),
                "任务还不能准备 PR"
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PULL_REQUEST_NOT_FOUND", "没有找到 PR 记录"));
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "没有找到 Agent 任务"));
    }

    private void ensureOwner(AgentTask task, Long userId) {
        if (!task.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_TASK_FORBIDDEN", "该任务不属于当前用户");
        }
    }

    private void ensurePrCreationState(AgentTask task) {
        if (task.getStatus() == AgentTaskStatus.WAITING_HUMAN_APPROVAL) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "准备 PR 前需要先审批补丁");
        }
        if (task.getStatus() != AgentTaskStatus.CREATING_PULL_REQUEST
                && task.getStatus() != AgentTaskStatus.FAILED_PR_CREATION) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "任务还不能准备 PR");
        }
    }

    private PullRequestPreflightCheckResponse taskStatusCheck(AgentTask task, boolean taskReady, boolean alreadyPrepared) {
        if (taskReady) {
            return check("TASK_STATUS", "任务状态", "PASS", "任务已进入 PR 准备状态。");
        }
        if (alreadyPrepared) {
            return check("TASK_STATUS", "任务状态", "PASS", "PR 记录已经准备完成。");
        }
        if (task.getStatus() == AgentTaskStatus.WAITING_HUMAN_APPROVAL) {
            return check("TASK_STATUS", "任务状态", "BLOCKED", "准备 PR 前需要先审批已测试通过的补丁。");
        }
        return check("TASK_STATUS", "任务状态", "BLOCKED", "当前任务状态为 " + task.getStatus() + "，还不能准备 PR。");
    }

    private PullRequestPreflightCheckResponse patchCheck(Optional<PatchRecord> latestPatch) {
        if (latestPatch.isEmpty()) {
            return check("PATCH_APPROVED", "补丁审批", "PENDING", "还没有生成补丁。");
        }
        PatchRecord patch = latestPatch.get();
        if (patch.getStatus() == PatchStatus.APPROVED) {
            return check("PATCH_APPROVED", "补丁审批", "PASS", "最新补丁已通过审批。");
        }
        return check("PATCH_APPROVED", "补丁审批", "BLOCKED", "最新补丁状态为 " + patch.getStatus() + "，需要先审批。");
    }

    private PullRequestPreflightCheckResponse testCheck(Optional<PatchRecord> latestPatch, Optional<TestRun> latestTestRun) {
        if (latestPatch.isEmpty()) {
            return check("SANDBOX_TEST", "沙箱测试", "PENDING", "沙箱测试正在等待补丁生成。");
        }
        if (latestTestRun.isEmpty()) {
            return check("SANDBOX_TEST", "沙箱测试", "PENDING", "最新补丁还没有沙箱测试记录。");
        }
        TestRun testRun = latestTestRun.get();
        if (testRun.getStatus() == TestRunStatus.PASSED) {
            return check("SANDBOX_TEST", "沙箱测试", "PASS", "最新沙箱测试已通过。");
        }
        return check("SANDBOX_TEST", "沙箱测试", "BLOCKED", "最新沙箱测试状态为 " + testRun.getStatus() + "，需要先通过测试。");
    }

    private PullRequestPreflightCheckResponse localDraftCheck(boolean localDraftReady, boolean alreadyPrepared) {
        if (alreadyPrepared) {
            return check("LOCAL_DRAFT", "本地草稿", "PASS", "本地分支和提交已经记录。");
        }
        if (localDraftReady) {
            return check("LOCAL_DRAFT", "本地草稿", "PASS", "本地分支和提交可以准备。");
        }
        return check("LOCAL_DRAFT", "本地草稿", "PENDING", "本地草稿正在等待任务就绪、补丁审批和沙箱测试通过。");
    }

    private PullRequestPreflightCheckResponse remoteGitHubCheck(boolean remotePublishingEnabled, boolean repositoryEligible, boolean tokenConfigured) {
        if (!remotePublishingEnabled) {
            return check("REMOTE_GITHUB", "远端 GitHub", "WARN", "远端 GitHub 发布已关闭，RepoPilot 会停在 DRAFT_READY。");
        }
        if (!repositoryEligible) {
            return check("REMOTE_GITHUB", "远端 GitHub", "WARN", "项目不是 github.com 仓库，将跳过远端 PR 创建。");
        }
        if (!tokenConfigured) {
            return check("REMOTE_GITHUB", "远端 GitHub", "BLOCKED", "该仓库已启用远端 GitHub 发布，但尚未配置 token。");
        }
        return check("REMOTE_GITHUB", "远端 GitHub", "PASS", "远端 GitHub PR 创建已配置。");
    }

    private PullRequestPreflightCheckResponse check(String code, String label, String status, String message) {
        return new PullRequestPreflightCheckResponse(code, label, status, message);
    }

    private void ensureApproved(PatchRecord patch) {
        if (patch.getStatus() != PatchStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_NOT_APPROVED", "准备 PR 前需要先审批最新补丁");
        }
    }

    private void ensureTestPassed(PatchRecord patch) {
        TestRun testRun = testRunRepository.findFirstByPatchIdOrderByCreatedAtDesc(patch.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PATCH_TEST_NOT_FOUND", "准备 PR 前需要先有沙箱测试记录"));
        if (testRun.getStatus() != TestRunStatus.PASSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_TEST_NOT_PASSED", "准备 PR 前最新沙箱测试必须通过");
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
        String title = "RepoPilot：" + task.getTitle();
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        return title.substring(0, MAX_TITLE_LENGTH);
    }

    private String body(AgentTask task, PatchRecord patch, PullRequestGitService.MaterializedPullRequest materialized) {
        return """
                由 RepoPilot 准备。

                任务：
                %s

                补丁：
                - 补丁 ID：%d
                - 基线分支：%s
                - 目标分支：%s
                - 状态：%s

                本地 Git：
                - Commit：%s

                说明：
                当前本地模式未启用 GitHub API 创建，因此 RepoPilot 只准备本地分支和提交，不创建外部 PR URL。
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
