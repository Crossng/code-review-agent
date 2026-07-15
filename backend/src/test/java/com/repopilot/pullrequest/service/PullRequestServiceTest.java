package com.repopilot.pullrequest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.agent.service.ProjectWriteGuardService;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.pullrequest.dto.PullRequestPreflightResponse;
import com.repopilot.pullrequest.repository.PullRequestRecordRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
import com.repopilot.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PullRequestServiceTest {

    @Mock
    private PullRequestRecordRepository pullRequestRecordRepository;

    @Mock
    private AgentTaskRepository agentTaskRepository;

    @Mock
    private PatchRecordRepository patchRecordRepository;

    @Mock
    private TestRunRepository testRunRepository;

    @Mock
    private PullRequestGitService pullRequestGitService;

    @Mock
    private GitHubPullRequestService gitHubPullRequestService;

    @Mock
    private ProjectWriteGuardService projectWriteGuardService;

    private PullRequestService service;

    @BeforeEach
    void setUp() {
        service = new PullRequestService(
                pullRequestRecordRepository,
                agentTaskRepository,
                patchRecordRepository,
                testRunRepository,
                pullRequestGitService,
                gitHubPullRequestService,
                projectWriteGuardService
        );
    }

    @Test
    void prepareCreatesLocalDraftWhenGithubPublishingIsDisabled() {
        Fixture fixture = fixture("file:///tmp/demo-repo");
        stubReadyTask(fixture);
        when(pullRequestRecordRepository.findFirstByPatchIdOrderByCreatedAtDesc(fixture.patch().getId()))
                .thenReturn(Optional.empty());
        when(pullRequestGitService.materialize(eq(fixture.task()), eq(fixture.patch()), anyString()))
                .thenReturn(new PullRequestGitService.MaterializedPullRequest(
                        "main",
                        "repopilot/task-20",
                        "abc123def456",
                        "RepoPilot: test"
                ));
        when(pullRequestRecordRepository.save(any(PullRequestRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gitHubPullRequestService.shouldPublish(fixture.project())).thenReturn(false);

        PullRequestRecord record = service.prepare(fixture.task().getId(), fixture.user().getId());

        assertEquals(PullRequestStatus.DRAFT_READY, record.getStatus());
        assertEquals("main", record.getBaseBranch());
        assertEquals("repopilot/task-20", record.getTargetBranch());
        assertEquals("abc123def456", record.getCommitSha());
        assertEquals(AgentTaskStatus.DONE, fixture.task().getStatus());
        verify(agentTaskRepository).save(fixture.task());
        verify(gitHubPullRequestService, never()).publish(any(Project.class), any(PullRequestRecord.class));
    }

    @Test
    void prepareReturnsExistingRecordWhenTaskIsAlreadyDone() {
        Fixture fixture = fixture("file:///tmp/demo-repo");
        fixture.task().setStatus(AgentTaskStatus.DONE);
        PullRequestRecord existingRecord = new PullRequestRecord(
                fixture.task(),
                fixture.patch(),
                com.repopilot.pullrequest.domain.PullRequestProvider.GITHUB,
                "RepoPilot: test",
                "Prepared by RepoPilot.",
                "main",
                "repopilot/task-20",
                "abc123def456",
                "RepoPilot: test",
                PullRequestStatus.DRAFT_READY
        );
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(pullRequestRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(fixture.task().getId()))
                .thenReturn(Optional.of(existingRecord));

        PullRequestRecord record = service.prepare(fixture.task().getId(), fixture.user().getId());

        assertSame(existingRecord, record);
        verify(projectWriteGuardService, never()).ensureProjectWriteSlot(any(), any(), anyString());
        verify(patchRecordRepository, never()).findFirstByAgentTaskIdOrderByCreatedAtDesc(any());
        verify(gitHubPullRequestService, never()).shouldPublish(any(Project.class));
    }

    @Test
    void prepareMarksRecordAndTaskFailedWhenGithubPublishingFails() {
        Fixture fixture = fixture("https://github.com/example/demo.git");
        stubReadyTask(fixture);
        when(pullRequestRecordRepository.findFirstByPatchIdOrderByCreatedAtDesc(fixture.patch().getId()))
                .thenReturn(Optional.empty());
        when(pullRequestGitService.materialize(eq(fixture.task()), eq(fixture.patch()), anyString()))
                .thenReturn(new PullRequestGitService.MaterializedPullRequest(
                        "main",
                        "repopilot/task-20",
                        "abc123def456",
                        "RepoPilot: test"
                ));
        when(pullRequestRecordRepository.save(any(PullRequestRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gitHubPullRequestService.shouldPublish(fixture.project())).thenReturn(true);
        when(gitHubPullRequestService.publish(eq(fixture.project()), any(PullRequestRecord.class)))
                .thenThrow(new ApiException(
                        HttpStatus.CONFLICT,
                        "GITHUB_TOKEN_NOT_CONFIGURED",
                        "GitHub publishing is enabled but no token is configured"
                ));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.prepare(fixture.task().getId(), fixture.user().getId())
        );

        assertEquals("GITHUB_TOKEN_NOT_CONFIGURED", exception.getCode());
        assertEquals(AgentTaskStatus.FAILED_PR_CREATION, fixture.task().getStatus());
        verify(agentTaskRepository).save(fixture.task());

        ArgumentCaptor<PullRequestRecord> captor = ArgumentCaptor.forClass(PullRequestRecord.class);
        verify(pullRequestRecordRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        PullRequestRecord failedRecord = captor.getAllValues().get(1);
        assertEquals(PullRequestStatus.FAILED, failedRecord.getStatus());
        assertEquals("GitHub publishing is enabled but no token is configured", failedRecord.getErrorMessage());
    }

    @Test
    void prepareMarksRecordOpenAndTaskDoneWhenGithubPublishingSucceeds() {
        Fixture fixture = fixture("https://github.com/example/demo.git");
        stubReadyTask(fixture);
        when(pullRequestRecordRepository.findFirstByPatchIdOrderByCreatedAtDesc(fixture.patch().getId()))
                .thenReturn(Optional.empty());
        when(pullRequestGitService.materialize(eq(fixture.task()), eq(fixture.patch()), anyString()))
                .thenReturn(new PullRequestGitService.MaterializedPullRequest(
                        "main",
                        "repopilot/task-20",
                        "abc123def456",
                        "RepoPilot: test"
                ));
        when(pullRequestRecordRepository.save(any(PullRequestRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gitHubPullRequestService.shouldPublish(fixture.project())).thenReturn(true);
        when(gitHubPullRequestService.publish(eq(fixture.project()), any(PullRequestRecord.class)))
                .thenReturn(new GitHubPullRequestService.GitHubPullRequest(
                        12,
                        "https://github.com/example/demo/pull/12"
                ));

        PullRequestRecord record = service.prepare(fixture.task().getId(), fixture.user().getId());

        assertEquals(PullRequestStatus.OPEN, record.getStatus());
        assertEquals(12, record.getPrNumber());
        assertEquals("https://github.com/example/demo/pull/12", record.getUrl());
        assertNotNull(record.getOpenedAt());
        assertEquals(AgentTaskStatus.DONE, fixture.task().getStatus());
        verify(agentTaskRepository).save(fixture.task());
    }

    @Test
    void preflightAllowsLocalDraftWhenReadyAndRemotePublishingIsDisabled() {
        Fixture fixture = fixture("file:///tmp/demo-repo");
        stubReadyTask(fixture);
        when(pullRequestRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(fixture.task().getId()))
                .thenReturn(Optional.empty());
        when(gitHubPullRequestService.isRemotePublishingEnabled()).thenReturn(false);
        when(gitHubPullRequestService.isRepositoryEligible(fixture.project())).thenReturn(false);
        when(gitHubPullRequestService.isTokenConfigured()).thenReturn(false);

        PullRequestPreflightResponse response = service.preflight(fixture.task().getId(), fixture.user().getId());

        assertTrue(response.canPrepare());
        assertTrue(response.localDraftReady());
        assertEquals("LOCAL_DRAFT_ONLY", response.publishMode());
        assertFalse(response.remotePublishingEnabled());
        assertFalse(response.remotePublishingWillRun());
        assertTrue(response.remoteReady());
        assertTrue(response.blockers().isEmpty());
    }

    @Test
    void preflightBlocksRemotePublishingWhenGithubTokenIsMissing() {
        Fixture fixture = fixture("https://github.com/example/demo.git");
        stubReadyTask(fixture);
        when(pullRequestRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(fixture.task().getId()))
                .thenReturn(Optional.empty());
        when(gitHubPullRequestService.isRemotePublishingEnabled()).thenReturn(true);
        when(gitHubPullRequestService.isRepositoryEligible(fixture.project())).thenReturn(true);
        when(gitHubPullRequestService.isTokenConfigured()).thenReturn(false);

        PullRequestPreflightResponse response = service.preflight(fixture.task().getId(), fixture.user().getId());

        assertFalse(response.canPrepare());
        assertTrue(response.localDraftReady());
        assertEquals("REMOTE_GITHUB_PR", response.publishMode());
        assertTrue(response.remotePublishingEnabled());
        assertTrue(response.remotePublishingWillRun());
        assertFalse(response.remoteReady());
        assertTrue(response.blockers().contains("GitHub remote publishing is enabled for this repository, but no token is configured."));
    }

    @Test
    void prepareKeepsApiExceptionOutOfRollbackRules() throws NoSuchMethodException {
        Method prepare = PullRequestService.class.getMethod("prepare", Long.class, Long.class);
        Transactional transactional = prepare.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertTrue(Arrays.asList(transactional.noRollbackFor()).contains(ApiException.class));
    }

    private void stubReadyTask(Fixture fixture) {
        when(agentTaskRepository.findById(fixture.task().getId())).thenReturn(Optional.of(fixture.task()));
        when(patchRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(fixture.task().getId()))
                .thenReturn(Optional.of(fixture.patch()));
        when(testRunRepository.findFirstByPatchIdOrderByCreatedAtDesc(fixture.patch().getId()))
                .thenReturn(Optional.of(fixture.testRun()));
    }

    private Fixture fixture(String repoUrl) {
        User user = new User("dev@example.com", "hash", "Dev", "USER");
        setId(user, 42L);
        Project project = new Project(user, repoUrl, "example/demo", "main");
        setId(project, 10L);
        project.setLocalPath("/workspace/repos/10/source");
        AgentTask task = new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "Test PR preparation",
                "Prepare a pull request for a safe patch."
        );
        setId(task, 20L);
        task.setStatus(AgentTaskStatus.CREATING_PULL_REQUEST);
        AgentRun run = new AgentRun(task);
        setId(run, 30L);
        PatchRecord patch = new PatchRecord(
                task,
                run,
                "main",
                "repopilot/task-20",
                "diff --git a/.repopilot/task-20-plan.md b/.repopilot/task-20-plan.md\n",
                "Safe patch"
        );
        setId(patch, 40L);
        patch.markApplied();
        patch.approve();
        TestRun testRun = new TestRun(
                run,
                patch,
                "mvn -q test",
                0,
                1500,
                "",
                TestRunStatus.PASSED
        );
        setId(testRun, 50L);
        return new Fixture(user, project, task, patch, testRun);
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }

    private record Fixture(
            User user,
            Project project,
            AgentTask task,
            PatchRecord patch,
            TestRun testRun
    ) {
    }
}
