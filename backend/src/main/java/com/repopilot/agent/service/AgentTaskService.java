package com.repopilot.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunReportSnapshot;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.dto.AgentRunReportResponse;
import com.repopilot.agent.dto.AgentRunReportSnapshotResponse;
import com.repopilot.agent.dto.AgentRunReportSnapshotSummaryResponse;
import com.repopilot.agent.dto.AgentRunReportSectionResponse;
import com.repopilot.agent.dto.CreateAgentTaskRequest;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentRunReportSnapshotRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.agent.worker.AgentWorkerGateway;
import com.repopilot.agent.worker.AgentWorkerStartResult;
import com.repopilot.common.ApiException;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.indexer.service.CodeSearchService;
import com.repopilot.modelcall.service.ModelCallLogService;
import com.repopilot.notification.service.TaskStreamService;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.patch.service.PatchGenerationService;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.service.SandboxTestService;
import com.repopilot.toolcall.service.ToolCallLogService;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentTaskService {

    private static final int MAX_REPAIR_ATTEMPTS = 2;
    private static final Pattern WORD_SPLITTER = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}_#]+");

    private final AgentTaskRepository agentTaskRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunReportSnapshotRepository agentRunReportSnapshotRepository;
    private final AgentStepRepository agentStepRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final CodeSearchService codeSearchService;
    private final PatchGenerationService patchGenerationService;
    private final PatchRecordRepository patchRecordRepository;
    private final SandboxTestService sandboxTestService;
    private final PatchRiskReviewService patchRiskReviewService;
    private final PatchDiffSafetyService patchDiffSafetyService;
    private final PatchRepairService patchRepairService;
    private final ModelCallLogService modelCallLogService;
    private final ToolCallLogService toolCallLogService;
    private final TaskStreamService taskStreamService;
    private final ProjectWriteGuardService projectWriteGuardService;
    private final AgentWorkerGateway agentWorkerGateway;
    private final TaskExecutor agentTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public AgentTaskService(
            AgentTaskRepository agentTaskRepository,
            AgentRunRepository agentRunRepository,
            AgentRunReportSnapshotRepository agentRunReportSnapshotRepository,
            AgentStepRepository agentStepRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            CodeSearchService codeSearchService,
            PatchGenerationService patchGenerationService,
            PatchRecordRepository patchRecordRepository,
            SandboxTestService sandboxTestService,
            PatchRiskReviewService patchRiskReviewService,
            PatchDiffSafetyService patchDiffSafetyService,
            PatchRepairService patchRepairService,
            ModelCallLogService modelCallLogService,
            ToolCallLogService toolCallLogService,
            TaskStreamService taskStreamService,
            ProjectWriteGuardService projectWriteGuardService,
            AgentWorkerGateway agentWorkerGateway,
            @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.agentTaskRepository = agentTaskRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentRunReportSnapshotRepository = agentRunReportSnapshotRepository;
        this.agentStepRepository = agentStepRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.codeSearchService = codeSearchService;
        this.patchGenerationService = patchGenerationService;
        this.patchRecordRepository = patchRecordRepository;
        this.sandboxTestService = sandboxTestService;
        this.patchRiskReviewService = patchRiskReviewService;
        this.patchDiffSafetyService = patchDiffSafetyService;
        this.patchRepairService = patchRepairService;
        this.modelCallLogService = modelCallLogService;
        this.toolCallLogService = toolCallLogService;
        this.taskStreamService = taskStreamService;
        this.projectWriteGuardService = projectWriteGuardService;
        this.agentWorkerGateway = agentWorkerGateway;
        this.agentTaskExecutor = agentTaskExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentTask create(CreateAgentTaskRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        if (!project.getOwner().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_FORBIDDEN", "Project does not belong to current user");
        }
        AgentTask task = new AgentTask(project, user, request.taskType(), request.title(), request.description());
        return agentTaskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public List<AgentTask> list(Long userId) {
        return agentTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<AgentTask> search(Long userId, Long projectId, AgentTaskStatus status, AgentTaskType taskType, String query) {
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        return agentTaskRepository.search(
                userId,
                projectId,
                status,
                taskType,
                normalizedQuery != null,
                normalizedQuery == null ? "" : "%" + normalizedQuery.toLowerCase(Locale.ROOT) + "%"
        );
    }

    @Transactional(readOnly = true)
    public AgentTask get(Long taskId, Long userId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found"));
        if (!task.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_TASK_FORBIDDEN", "Task does not belong to current user");
        }
        return task;
    }

    @Transactional
    public AgentRun run(Long taskId, Long userId) {
        AgentTask task = get(taskId, userId);
        if (task.getStatus() != AgentTaskStatus.CREATED && task.getStatus() != AgentTaskStatus.FAILED_TEST) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "Task cannot be started from current status");
        }
        projectWriteGuardService.ensureProjectWriteSlot(
                task,
                Set.of(AgentTaskStatus.CREATED, AgentTaskStatus.FAILED_TEST),
                "Task cannot be started from current status"
        );
        AgentRun run = createRun(task);
        submitRunAfterCommit(run.getId());
        return run;
    }

    @Transactional
    public PatchRecord regeneratePatch(Long taskId, Long userId) {
        AgentTask task = get(taskId, userId);
        if (!regeneratableStatuses().contains(task.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "Patch cannot be regenerated from current task status");
        }
        projectWriteGuardService.ensureProjectWriteSlot(
                task,
                regeneratableStatuses(),
                "Patch cannot be regenerated from current task status"
        );
        AgentRun run = createRun(task);
        return executeRun(task, run, false).patch();
    }

    private AgentRun createRun(AgentTask task) {
        AgentRun run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task.setStatus(AgentTaskStatus.GENERATING_PATCH);
        saveTaskProgress(task, run);
        return run;
    }

    private void submitRunAfterCommit(Long runId) {
        Runnable command = () -> executeRunInNewTransaction(runId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            agentTaskExecutor.execute(command);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                agentTaskExecutor.execute(command);
            }
        });
    }

    private void executeRunInNewTransaction(Long runId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AgentRun run = agentRunRepository.findById(runId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
                executeRun(run.getAgentTask(), run, true);
            });
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markRunFailed(runId, exception));
        }
    }

    private void markRunFailed(Long runId, RuntimeException exception) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            AgentTask task = run.getAgentTask();
            task.setStatus(AgentTaskStatus.FAILED_PATCH_GENERATION);
            run.markFailed(exception.getMessage());
            saveTaskProgress(task, run);
            saveRunProgress(task, run);
            completeTaskStream(task, run, "Agent 运行意外失败");
        });
    }

    private RunExecution executeRun(AgentTask task, AgentRun run, boolean cancellationAware) {
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new RunExecution(run, null);
        }
        startAgentWorkerIfEnabled(task, run);
        List<String> queries = candidateQueries(task);
        RunContext context = toolCallLogService.record(
                run,
                "load_task_context",
                Map.of("taskId", task.getId()),
                () -> RunContext.from(task)
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new RunExecution(run, null);
        }
        PlanOutput plan = modelCallLogService.record(
                run,
                "plan_task",
                Map.of("title", task.getTitle(), "description", task.getDescription(), "taskType", task.getTaskType()),
                () -> plan(task)
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new RunExecution(run, null);
        }
        RetrievalOutput retrieval = toolCallLogService.record(
                run,
                "search_code",
                Map.of("projectId", task.getProject().getId(), "queries", queries, "limitPerQuery", 8),
                () -> retrieveContext(task),
                RetrievalAuditOutput::from
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new RunExecution(run, null);
        }
        PatchGenerationPrompt patchPrompt = PatchGenerationPrompt.from(task, retrieval);
        PatchRecord patch = modelCallLogService.record(
                run,
                "generate_patch",
                patchPrompt,
                () -> patchGenerationService.generatePatch(task, run, retrieval.results()),
                generatedPatch -> new ModelCallLogService.ModelMetadata(
                        generatedPatch.getGenerationProvider(),
                        generatedPatch.getGenerationModel()
                ),
                PatchOutput::from
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new RunExecution(run, patch);
        }

        saveStep(run, "load_task_context", AgentStepStatus.SUCCESS, Map.of("taskId", task.getId()), context);
        saveStep(run, "plan_task", AgentStepStatus.SUCCESS, Map.of("title", task.getTitle(), "taskType", task.getTaskType()), plan);
        saveStep(
                run,
                "retrieve_context",
                AgentStepStatus.SUCCESS,
                Map.of("queries", retrieval.queries(), "limitPerQuery", 8),
                retrieval
        );
        saveStep(run, "generate_patch", AgentStepStatus.SUCCESS, patchPrompt, PatchOutput.from(patch));

        TestRun passedTestRun = null;
        try {
            for (int repairAttempt = 0; repairAttempt <= MAX_REPAIR_ATTEMPTS; repairAttempt++) {
                if (stopIfCancelled(task, run, cancellationAware)) {
                    return new RunExecution(run, patch);
                }
                PatchValidation validation = validatePatch(task, run, patch, cancellationAware);
                if (validation.cancelled()) {
                    return new RunExecution(run, patch);
                }
                if (validation.patchApplicationFailed()) {
                    return new RunExecution(run, patch);
                }
                if (validation.testRun().getStatus() == TestRunStatus.PASSED) {
                    passedTestRun = validation.testRun();
                    break;
                }
                if (repairAttempt >= MAX_REPAIR_ATTEMPTS) {
                    task.setStatus(AgentTaskStatus.FAILED_TEST);
                    run.markFailed("沙箱 Maven 测试失败，已用完 " + MAX_REPAIR_ATTEMPTS + " 次修复尝试");
                    saveTaskProgress(task, run);
                    saveRunProgress(task, run);
                    completeTaskStream(task, run, "Agent 运行在修复尝试后失败");
                    return new RunExecution(run, patch);
                }
                int attemptNumber = repairAttempt + 1;
                task.setStatus(AgentTaskStatus.REPAIRING);
                saveTaskProgress(task, run);
                if (stopIfCancelled(task, run, cancellationAware)) {
                    return new RunExecution(run, patch);
                }
                PatchRecord failedPatch = patch;
                TestRun failedTestRun = validation.testRun();
                try {
                    PatchRecord repairedPatch = modelCallLogService.record(
                            run,
                            "repair_patch",
                            Map.of(
                                    "patchId", failedPatch.getId(),
                                    "testRunId", failedTestRun.getId(),
                                    "attempt", attemptNumber,
                                    "maxAttempts", MAX_REPAIR_ATTEMPTS
                            ),
                            () -> patchRepairService.repairMavenFailure(
                                    task,
                                    run,
                                    failedPatch,
                                    failedTestRun,
                                    attemptNumber
                            ),
                            PatchOutput::from
                    );
                    if (stopIfCancelled(task, run, cancellationAware)) {
                        return new RunExecution(run, failedPatch);
                    }
                    saveStep(
                            run,
                            "repair_patch",
                            AgentStepStatus.SUCCESS,
                            Map.of("patchId", failedPatch.getId(), "testRunId", failedTestRun.getId(), "attempt", attemptNumber),
                            PatchOutput.from(repairedPatch)
                    );
                    patch = repairedPatch;
                } catch (ApiException repairException) {
                    saveStep(
                            run,
                            "repair_patch",
                            AgentStepStatus.FAILED,
                            Map.of("patchId", failedPatch.getId(), "testRunId", failedTestRun.getId(), "attempt", attemptNumber),
                            Map.of("code", repairException.getCode(), "message", repairException.getMessage()),
                            repairException.getMessage()
                    );
                    task.setStatus(AgentTaskStatus.FAILED_TEST);
                    run.markFailed("沙箱 Maven 测试失败，RepairAgent 未能修复：" + repairException.getMessage());
                    saveTaskProgress(task, run);
                    saveRunProgress(task, run);
                    completeTaskStream(task, run, "Agent 运行在修复阶段失败");
                    return new RunExecution(run, failedPatch);
                }
            }
            if (stopIfCancelled(task, run, cancellationAware)) {
                return new RunExecution(run, patch);
            }
            if (passedTestRun == null) {
                task.setStatus(AgentTaskStatus.FAILED_TEST);
                run.markFailed("沙箱 Maven 测试失败");
                saveTaskProgress(task, run);
                saveRunProgress(task, run);
                completeTaskStream(task, run, "Agent 运行在沙箱测试阶段失败");
                return new RunExecution(run, patch);
            }
            task.setStatus(AgentTaskStatus.REVIEWING_PATCH);
            saveTaskProgress(task, run);
            if (stopIfCancelled(task, run, cancellationAware)) {
                return new RunExecution(run, patch);
            }
            PatchRecord reviewedPatch = patch;
            TestRun reviewedTestRun = passedTestRun;
            PatchRiskReviewService.PatchRiskReview reviewOutput = modelCallLogService.record(
                    run,
                    "review_patch",
                    Map.of("patchId", reviewedPatch.getId(), "testRunId", reviewedTestRun.getId()),
                    () -> patchRiskReviewService.review(reviewedPatch, reviewedTestRun)
            );
            if (stopIfCancelled(task, run, cancellationAware)) {
                return new RunExecution(run, patch);
            }
            saveStep(
                    run,
                    "review_patch",
                    AgentStepStatus.SUCCESS,
                    Map.of("patchId", reviewedPatch.getId(), "testRunId", reviewedTestRun.getId()),
                    reviewOutput
            );
        } catch (ApiException exception) {
            saveStep(
                    run,
                    "apply_patch",
                    AgentStepStatus.FAILED,
                    Map.of("patchId", patch.getId()),
                    Map.of("code", exception.getCode(), "message", exception.getMessage()),
                    exception.getMessage()
            );
            task.setStatus(AgentTaskStatus.FAILED_TEST);
            run.markFailed(exception.getMessage());
            saveTaskProgress(task, run);
            saveRunProgress(task, run);
            completeTaskStream(task, run, "Agent 运行在应用补丁时失败");
            return new RunExecution(run, patch);
        }

        if (stopIfCancelled(task, run, cancellationAware)) {
            return new RunExecution(run, patch);
        }
        task.setStatus(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        saveTaskProgress(task, run);
        saveStep(
                run,
                "waiting_human_approval",
                AgentStepStatus.PENDING,
                Map.of("patchId", patch.getId(), "status", patch.getStatus()),
                null
        );
        run.markSuccess();
        saveRunProgress(task, run);
        completeTaskStream(task, run, "Agent 运行已进入人工审批");
        return new RunExecution(run, patch);
    }

    private PatchValidation validatePatch(AgentTask task, AgentRun run, PatchRecord patch, boolean cancellationAware) {
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        task.setStatus(AgentTaskStatus.APPLYING_PATCH_IN_SANDBOX);
        saveTaskProgress(task, run);
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        PatchDiffSafetyService.PatchDiffSafetyReport safetyReport = toolCallLogService.record(
                run,
                "validate_patch_safety",
                Map.of("patchId", patch.getId()),
                () -> patchDiffSafetyService.review(patch)
        );
        if (!safetyReport.safe()) {
            saveStep(
                    run,
                    "validate_patch_safety",
                    AgentStepStatus.FAILED,
                    Map.of("patchId", patch.getId()),
                    safetyReport,
                    "补丁 diff 未通过安全预检"
            );
            task.setStatus(AgentTaskStatus.FAILED_PATCH_GENERATION);
            run.markFailed("补丁 diff 未通过安全预检");
            saveTaskProgress(task, run);
            saveRunProgress(task, run);
            completeTaskStream(task, run, "Agent 运行在补丁安全预检阶段失败");
            return new PatchValidation(null, true, false);
        }
        saveStep(run, "validate_patch_safety", AgentStepStatus.SUCCESS, Map.of("patchId", patch.getId()), safetyReport);
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        SandboxTestService.SandboxWorkspace workspace = toolCallLogService.record(
                run,
                "prepare_sandbox",
                Map.of("runId", run.getId(), "patchId", patch.getId()),
                () -> sandboxTestService.prepareWorkspace(run, patch),
                SandboxWorkspaceOutput::from
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        SandboxTestService.CommandResult applyResult = toolCallLogService.record(
                run,
                "apply_patch",
                Map.of("patchId", patch.getId(), "patchPath", workspace.patchPath().toString()),
                () -> sandboxTestService.applyPatch(workspace),
                SandboxCommandOutput::from
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        if (!applyResult.success()) {
            saveStep(
                    run,
                    "apply_patch",
                    AgentStepStatus.FAILED,
                    Map.of("patchId", patch.getId(), "patchPath", workspace.patchPath().toString()),
                    SandboxCommandOutput.from(applyResult),
                    "补丁在沙箱中应用失败"
            );
            task.setStatus(AgentTaskStatus.FAILED_PATCH_GENERATION);
            run.markFailed("补丁在沙箱中应用失败");
            saveTaskProgress(task, run);
            saveRunProgress(task, run);
            completeTaskStream(task, run, "Agent 运行在应用补丁时失败");
            return new PatchValidation(null, true, false);
        }
        patch.markApplied();
        patchRecordRepository.save(patch);
        saveStep(
                run,
                "apply_patch",
                AgentStepStatus.SUCCESS,
                Map.of("patchId", patch.getId(), "patchPath", workspace.patchPath().toString()),
                SandboxCommandOutput.from(applyResult)
        );

        task.setStatus(AgentTaskStatus.RUNNING_TESTS);
        saveTaskProgress(task, run);
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        TestRun testRun = toolCallLogService.record(
                run,
                "run_maven_test",
                Map.of("patchId", patch.getId(), "command", "mvn -q test"),
                () -> sandboxTestService.runMavenTest(run, patch, workspace),
                TestRunOutput::from
        );
        if (stopIfCancelled(task, run, cancellationAware)) {
            return new PatchValidation(null, false, true);
        }
        if (testRun.getStatus() != TestRunStatus.PASSED) {
            saveStep(
                    run,
                    "run_tests",
                    AgentStepStatus.FAILED,
                    Map.of("patchId", patch.getId(), "command", "mvn -q test"),
                    TestRunOutput.from(testRun),
                    "沙箱 Maven 测试失败"
            );
            return new PatchValidation(testRun, false, false);
        }
        saveStep(
                run,
                "run_tests",
                AgentStepStatus.SUCCESS,
                Map.of("patchId", patch.getId(), "command", "mvn -q test"),
                TestRunOutput.from(testRun)
        );
        return new PatchValidation(testRun, false, false);
    }

    @Transactional
    public AgentTask cancel(Long taskId, Long userId) {
        AgentTask task = get(taskId, userId);
        task.setStatus(AgentTaskStatus.CANCELLED);
        AgentTask savedTask = agentTaskRepository.save(task);
        AgentRun run = savedTask.getCurrentRun();
        if (run != null && run.getStatus() == AgentRunStatus.RUNNING) {
            run.markCancelled("用户已取消任务");
            agentRunRepository.save(run);
        }
        taskStreamService.publishTaskUpdated(savedTask, run);
        completeTaskStream(savedTask, run, "任务已取消");
        return savedTask;
    }

    @Transactional(readOnly = true)
    public List<AgentStep> steps(Long taskId, Long userId) {
        AgentTask task = get(taskId, userId);
        if (task.getCurrentRun() == null) {
            return List.of();
        }
        return agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(task.getCurrentRun().getId());
    }

    @Transactional(readOnly = true)
    public AgentRunReportResponse runReport(Long taskId, Long userId) {
        AgentTask task = get(taskId, userId);
        return buildCurrentRunReport(task);
    }

    @Transactional
    public AgentRunReportSnapshotResponse createRunReportSnapshot(Long taskId, Long userId) {
        AgentTask task = get(taskId, userId);
        AgentRunReportResponse report = buildCurrentRunReport(task);
        AgentRunReportSnapshot snapshot = agentRunReportSnapshotRepository.save(
                new AgentRunReportSnapshot(task, task.getUser(), report)
        );
        return AgentRunReportSnapshotResponse.from(snapshot);
    }

    @Transactional(readOnly = true)
    public List<AgentRunReportSnapshotSummaryResponse> listRunReportSnapshots(Long taskId, Long userId, int limit) {
        AgentTask task = get(taskId, userId);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return agentRunReportSnapshotRepository
                .findByAgentTaskIdOrderByReportGeneratedAtDesc(task.getId(), PageRequest.of(0, safeLimit))
                .stream()
                .map(AgentRunReportSnapshotSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentRunReportSnapshotResponse getRunReportSnapshot(Long taskId, Long userId, Long snapshotId) {
        AgentTask task = get(taskId, userId);
        AgentRunReportSnapshot snapshot = agentRunReportSnapshotRepository
                .findByIdAndAgentTaskId(snapshotId, task.getId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "AGENT_RUN_REPORT_SNAPSHOT_NOT_FOUND",
                        "Agent run report snapshot not found"
                ));
        return AgentRunReportSnapshotResponse.from(snapshot);
    }

    private AgentRunReportResponse buildCurrentRunReport(AgentTask task) {
        AgentRun run = task.getCurrentRun();
        if (run == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent task has no current run");
        }
        List<AgentStep> steps = agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        Instant generatedAt = Instant.now();
        List<AgentRunReportSectionResponse> sections = runReportSections(steps);
        String markdown = runReportMarkdown(task, run, generatedAt, sections);
        return new AgentRunReportResponse(
                task.getId(),
                run.getId(),
                task.getProject().getId(),
                task.getProject().getRepoFullName(),
                task.getTaskType(),
                task.getTitle(),
                task.getStatus(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                generatedAt,
                sections,
                markdown
        );
    }

    private List<AgentRunReportSectionResponse> runReportSections(List<AgentStep> steps) {
        List<AgentRunReportSectionResponse> sections = new ArrayList<>();
        addWorkerSection(sections, latestStepByName(steps, "agent_worker_start"));
        addPlanSection(sections, latestStepByName(steps, "plan_task"));
        addRetrievalSection(sections, latestStepByName(steps, "retrieve_context"));
        addPatchSection(sections, latestStepByName(steps, "generate_patch"));
        addSafetySection(sections, latestStepByName(steps, "validate_patch_safety"));
        addTestSection(sections, latestStepByName(steps, "run_tests"));
        addReviewSection(sections, latestStepByName(steps, "review_patch"));
        addApprovalSection(sections, latestStepByName(steps, "waiting_human_approval"));
        return sections;
    }

    private void addWorkerSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        List<String> graphNodes = stringArray(output, "graph_nodes");
        if (graphNodes.isEmpty()) {
            graphNodes = stringArray(output, "graphNodes");
        }
        List<String> facts = new ArrayList<>();
        String runId = longText(output, "run_id", "Worker run #");
        if (runId == null) {
            runId = longText(output, "runId", "Worker run #");
        }
        if (runId != null) {
            facts.add(runId);
        }
        String accepted = booleanText(output, "accepted", "accepted=");
        if (accepted != null) {
            facts.add(accepted);
        }
        String workerStatus = text(output, "status", null);
        if (workerStatus != null) {
            facts.add(workerStatus);
        }
        if (!graphNodes.isEmpty()) {
            facts.add("Graph nodes: " + graphNodes.size());
        }
        String summary = step.getStatus() == AgentStepStatus.SUCCESS
                ? "后端已把本次 run 的启动契约发送给 Python Agent Worker。"
                : "后端尝试启动 Python Agent Worker 失败，当前仍保留 Spring Boot 本地执行兜底。";
        sections.add(section(
                "worker",
                "Agent Worker 启动桥",
                step,
                summary,
                facts,
                graphNodes.stream().limit(10).toList()
        ));
    }

    private void addPlanSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        List<String> queries = stringArray(output, "searchQueries");
        List<JsonNode> planSteps = array(output, "steps");
        List<String> highlights = new ArrayList<>();
        for (int index = 0; index < planSteps.size() && index < 5; index++) {
            JsonNode planStep = planSteps.get(index);
            int order = intValue(planStep, "order", index + 1);
            String title = text(planStep, "title", "计划步骤");
            String reason = text(planStep, "reason", "");
            highlights.add(reason.isBlank() ? order + ". " + title : order + ". " + title + " - " + reason);
        }
        sections.add(section(
                "planner",
                "任务规划",
                step,
                text(output, "summary", "Planner 已生成实现计划。"),
                queries.isEmpty() ? List.of() : List.of("检索词：" + summarizeList(queries, 4)),
                highlights
        ));
    }

    private void addRetrievalSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        List<JsonNode> results = array(output, "results");
        List<String> queries = stringArray(output, "queries");
        List<String> facts = new ArrayList<>();
        facts.add("去重代码片段：" + results.size());
        facts.add("检索词数量：" + queries.size());
        facts.addAll(resultCountFacts(output).stream().limit(4).toList());
        List<String> highlights = results.stream()
                .limit(5)
                .map(result -> {
                    String filePath = text(result, "filePath", "Unknown file");
                    String qualifiedName = text(result, "qualifiedName", "");
                    String summary = text(result, "summary", text(result, "preview", ""));
                    String subject = qualifiedName.isBlank()
                            ? filePath + sourceRange(result)
                            : filePath + sourceRange(result) + " (" + qualifiedName + ")";
                    return summary.isBlank() ? subject : subject + " - " + truncate(summary, 140);
                })
                .toList();
        sections.add(section(
                "retrieval",
                "检索到的代码上下文",
                step,
                "通过 " + queries.size() + " 个检索词命中 " + results.size() + " 个去重代码片段。",
                facts,
                highlights
        ));
    }

    private void addPatchSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        List<String> facts = compactList(
                longText(output, "patchId", "补丁 #"),
                text(output, "status", null),
                text(output, "generationMode", null),
                text(output, "generationProvider", null),
                modelFact(output),
                branchPair(output)
        );
        String generationMode = text(output, "generationMode", "");
        String generationProvider = text(output, "generationProvider", "");
        String generationModel = text(output, "generationModel", "");
        List<String> highlights = compactList(
                generationMode.isBlank() ? null : "生成模式：" + generationMode,
                generationProvider.isBlank() ? null : "生成来源：" + generationProvider,
                generationModel.isBlank() ? null : "模型：" + generationModel
        );
        sections.add(section(
                "patch",
                "生成的补丁产物",
                step,
                text(output, "summary", "Coder 已生成补丁产物。"),
                facts,
                highlights
        ));
    }

    private void addSafetySection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        String safe = output.has("safe") && output.path("safe").isBoolean() ? String.valueOf(output.path("safe").asBoolean()) : null;
        sections.add(section(
                "safety",
                "补丁安全门",
                step,
                safe == null ? "补丁安全预检已完成。" : "补丁安全预检判定 diff 为" + ("true".equals(safe) ? "安全" : "不安全") + "。",
                safe == null ? List.of() : List.of("safe=" + safe),
                stringArray(output, "reasons").stream().limit(4).toList()
        ));
    }

    private void addTestSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        sections.add(section(
                "tests",
                "沙箱测试结果",
                step,
                text(output, "logExcerpt", "沙箱测试执行已完成。"),
                compactList(
                        text(output, "command", null),
                        text(output, "status", null),
                        output.has("durationMs") && output.path("durationMs").isNumber() ? output.path("durationMs").asInt() + " ms" : null,
                        output.has("exitCode") && output.path("exitCode").isNumber() ? "退出码 " + output.path("exitCode").asInt() : null
                ),
                List.of()
        ));
    }

    private void addReviewSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode output = jsonNode(step.getOutputJson());
        List<JsonNode> findings = array(output, "findings");
        List<String> highlights = findings.isEmpty()
                ? List.of("没有自动审查发现。")
                : findings.stream()
                .limit(5)
                .map(finding -> String.join(" - ", compactList(
                        text(finding, "severity", null),
                        text(finding, "code", null),
                        text(finding, "message", null)
                )))
                .toList();
        sections.add(section(
                "review",
                "自动补丁审查",
                step,
                text(output, "summary", "自动补丁审查已完成。"),
                compactList(text(output, "riskLevel", null)),
                highlights
        ));
    }

    private void addApprovalSection(List<AgentRunReportSectionResponse> sections, AgentStep step) {
        if (step == null) {
            return;
        }
        JsonNode input = jsonNode(step.getInputJson());
        sections.add(section(
                "approval",
                "人工审批检查点",
                step,
                "本次运行已在创建 PR 前暂停，等待人工审查并审批补丁。",
                compactList(longText(input, "patchId", "补丁 #"), text(input, "status", null)),
                List.of()
        ));
    }

    private AgentRunReportSectionResponse section(
            String key,
            String title,
            AgentStep step,
            String summary,
            List<String> facts,
            List<String> highlights
    ) {
        return new AgentRunReportSectionResponse(
                key,
                title,
                step.getStepName(),
                step.getStatus(),
                step.getFinishedAt(),
                summary,
                facts,
                highlights
        );
    }

    private AgentStep latestStepByName(List<AgentStep> steps, String stepName) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            AgentStep step = steps.get(index);
            if (step.getStepName().equals(stepName)) {
                return step;
            }
        }
        return null;
    }

    private JsonNode jsonNode(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private List<JsonNode> array(JsonNode node, String fieldName) {
        JsonNode array = node.path(fieldName);
        if (!array.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private List<String> stringArray(JsonNode node, String fieldName) {
        return array(node, fieldName).stream()
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> resultCountFacts(JsonNode output) {
        JsonNode counts = output.path("resultCountByQuery");
        if (!counts.isObject()) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = counts.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            facts.add(field.getKey() + ": " + field.getValue().asInt(0));
        }
        return facts;
    }

    private String text(JsonNode node, String fieldName, String fallback) {
        JsonNode value = node.path(fieldName);
        if (value.isTextual() && !value.asText().isBlank()) {
            return value.asText();
        }
        return fallback;
    }

    private int intValue(JsonNode node, String fieldName, int fallback) {
        JsonNode value = node.path(fieldName);
        return value.isInt() ? value.asInt() : fallback;
    }

    private String longText(JsonNode node, String fieldName, String prefix) {
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? prefix + value.asLong() : null;
    }

    private String booleanText(JsonNode node, String fieldName, String prefix) {
        JsonNode value = node.path(fieldName);
        return value.isBoolean() ? prefix + value.asBoolean() : null;
    }

    private List<String> compactList(String... values) {
        List<String> compact = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                compact.add(value);
            }
        }
        return compact;
    }

    private String summarizeList(List<String> values, int maxItems) {
        List<String> visible = values.stream().limit(maxItems).toList();
        String suffix = values.size() > maxItems ? "，另 " + (values.size() - maxItems) + " 个" : "";
        return String.join(", ", visible) + suffix;
    }

    private String sourceRange(JsonNode node) {
        JsonNode start = node.path("startLine");
        if (!start.isNumber()) {
            return "";
        }
        JsonNode end = node.path("endLine");
        if (!end.isNumber() || end.asInt() == start.asInt()) {
            return ":" + start.asInt();
        }
        return ":" + start.asInt() + "-" + end.asInt();
    }

    private String branchPair(JsonNode node) {
        String base = text(node, "baseBranch", null);
        String target = text(node, "targetBranch", null);
        return base == null || target == null ? null : base + " -> " + target;
    }

    private String modelFact(JsonNode node) {
        String model = text(node, "generationModel", null);
        return model == null ? null : "模型：" + model;
    }

    private String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength - 1) + "..." : value;
    }

    private String runReportMarkdown(
            AgentTask task,
            AgentRun run,
            Instant generatedAt,
            List<AgentRunReportSectionResponse> sections
    ) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# RepoPilot Agent 运行报告\n\n");
        markdown.append("- 任务：#").append(task.getId()).append(" ").append(task.getTitle()).append("\n");
        markdown.append("- 项目：").append(task.getProject().getRepoFullName()).append("\n");
        markdown.append("- 任务类型：").append(task.getTaskType()).append("\n");
        markdown.append("- 任务状态：").append(task.getStatus()).append("\n");
        markdown.append("- 运行：#").append(run.getId()).append(" ").append(run.getStatus()).append("\n");
        markdown.append("- 开始时间：").append(run.getStartedAt()).append("\n");
        markdown.append("- 完成时间：").append(run.getFinishedAt() == null ? "未完成" : run.getFinishedAt()).append("\n");
        markdown.append("- 报告生成时间：").append(generatedAt).append("\n\n");
        for (AgentRunReportSectionResponse section : sections) {
            markdown.append("## ").append(section.title()).append("\n\n");
            markdown.append("_").append(section.stepName()).append(" / ").append(section.status());
            if (section.finishedAt() != null) {
                markdown.append(" / ").append(section.finishedAt());
            }
            markdown.append("_\n\n");
            markdown.append(section.summary()).append("\n\n");
            appendMarkdownList(markdown, "事实", section.facts());
            appendMarkdownList(markdown, "重点", section.highlights());
        }
        return markdown.toString();
    }

    private void appendMarkdownList(StringBuilder markdown, String title, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        markdown.append(title).append(":\n");
        for (String value : values) {
            markdown.append("- ").append(value).append("\n");
        }
        markdown.append("\n");
    }

    private boolean stopIfCancelled(AgentTask task, AgentRun run, boolean cancellationAware) {
        if (!cancellationAware) {
            return false;
        }
        AgentTaskStatus latestStatus = agentTaskRepository.findStatusById(task.getId()).orElse(task.getStatus());
        if (latestStatus != AgentTaskStatus.CANCELLED) {
            return false;
        }
        task.setStatus(AgentTaskStatus.CANCELLED);
        run.markCancelled("Agent 运行已取消");
        saveTaskProgress(task, run);
        saveRunProgress(task, run);
        completeTaskStream(task, run, "Agent 运行已取消");
        return true;
    }

    private Set<AgentTaskStatus> regeneratableStatuses() {
        return Set.of(
                AgentTaskStatus.WAITING_HUMAN_APPROVAL,
                AgentTaskStatus.CANCELLED,
                AgentTaskStatus.FAILED_TEST,
                AgentTaskStatus.FAILED_PATCH_GENERATION
        );
    }

    private void saveTaskProgress(AgentTask task, AgentRun run) {
        agentTaskRepository.save(task);
        taskStreamService.publishTaskUpdated(task, run);
    }

    private void saveRunProgress(AgentTask task, AgentRun run) {
        agentRunRepository.save(run);
        taskStreamService.publishTaskUpdated(task, run);
    }

    private void completeTaskStream(AgentTask task, AgentRun run, String message) {
        taskStreamService.publishStreamComplete(task, run, message);
    }

    private void startAgentWorkerIfEnabled(AgentTask task, AgentRun run) {
        if (!agentWorkerGateway.isEnabled()) {
            return;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("runId", run.getId());
        input.put("taskId", task.getId());
        input.put("projectId", task.getProject().getId());
        input.put("repoPath", task.getProject().getLocalPath());
        input.put("baseBranch", task.getProject().getDefaultBranch());
        try {
            AgentWorkerStartResult result = agentWorkerGateway.startRun(run);
            saveStep(run, "agent_worker_start", AgentStepStatus.SUCCESS, input, result);
        } catch (RuntimeException exception) {
            saveStep(
                    run,
                    "agent_worker_start",
                    AgentStepStatus.FAILED,
                    input,
                    Map.of("code", workerErrorCode(exception), "message", safeErrorMessage(exception)),
                    safeErrorMessage(exception)
            );
        }
    }

    private String workerErrorCode(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return apiException.getCode();
        }
        return exception.getClass().getSimpleName();
    }

    private String safeErrorMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void saveStep(AgentRun run, String name, AgentStepStatus status, Object input, Object output) {
        saveStep(run, name, status, input, output, null);
    }

    private void saveStep(AgentRun run, String name, AgentStepStatus status, Object input, Object output, String errorMessage) {
        AgentStep step = agentStepRepository.save(new AgentStep(
                run,
                name,
                status,
                json(input),
                output == null ? null : json(output),
                errorMessage
        ));
        taskStreamService.publishStepRecorded(run.getAgentTask(), run, step);
    }

    private PlanOutput plan(AgentTask task) {
        List<String> queries = candidateQueries(task);
        List<PlanStep> steps = List.of(
                new PlanStep(1, "加载任务和项目上下文", "将本次运行绑定到项目 #" + task.getProject().getId()),
                new PlanStep(2, "检索仓库上下文", "使用 " + queries + " 检索已索引代码片段"),
                new PlanStep(3, "生成 Spring 感知的 unified diff", "优先评估内置 Spring Coder recipe，再回退到基于检索上下文的安全 Coder 计划"),
                new PlanStep(4, "校验补丁安全性", "沙箱应用前拒绝不安全 diff 路径"),
                new PlanStep(5, "运行 Maven 验证", "在 Docker 沙箱应用 diff 并执行 mvn test")
        );
        return new PlanOutput(
                "为任务准备实现上下文：" + task.getTitle(),
                steps,
                queries,
                "生成补丁前优先使用检索到的 Controller/Service/Mapper/Entity 代码片段。"
        );
    }

    private RetrievalOutput retrieveContext(AgentTask task) {
        List<String> queries = candidateQueries(task);
        Map<Long, CodeSearchResultResponse> uniqueResults = new LinkedHashMap<>();
        Map<String, Integer> resultCountByQuery = new LinkedHashMap<>();
        for (String query : queries) {
            CodeSearchResponse response = codeSearchService.search(task.getProject().getId(), query, 8);
            resultCountByQuery.put(query, response.results().size());
            for (CodeSearchResultResponse result : response.results()) {
                uniqueResults.putIfAbsent(result.chunkId(), result);
            }
            if (uniqueResults.size() >= 12) {
                break;
            }
        }
        List<CodeSearchResultResponse> results = new ArrayList<>(uniqueResults.values());
        if (results.size() > 12) {
            results = results.subList(0, 12);
        }
        return new RetrievalOutput(queries, resultCountByQuery, results);
    }

    private List<String> candidateQueries(AgentTask task) {
        Set<String> queries = new LinkedHashSet<>();
        addQuery(queries, task.getTitle());
        addQuery(queries, task.getDescription());
        for (String token : WORD_SPLITTER.split(task.getTitle() + " " + task.getDescription())) {
            String normalized = token.trim();
            if (normalized.length() >= 4 || normalized.toLowerCase(Locale.ROOT).contains("user")) {
                addQuery(queries, normalized);
            }
        }
        if (queries.isEmpty()) {
            addQuery(queries, task.getTitle());
        }
        return queries.stream().limit(8).toList();
    }

    private void addQuery(Set<String> queries, String value) {
        if (value != null && !value.isBlank()) {
            queries.add(value.trim());
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_STEP_JSON_FAILED", exception.getMessage());
        }
    }

    private record RunContext(
            Long taskId,
            Long projectId,
            String projectRepo,
            String taskType,
            String title,
            String description
    ) {

        static RunContext from(AgentTask task) {
            return new RunContext(
                    task.getId(),
                    task.getProject().getId(),
                    task.getProject().getRepoFullName(),
                    task.getTaskType().name(),
                    task.getTitle(),
                    task.getDescription()
            );
        }
    }

    private record PlanOutput(String summary, List<PlanStep> steps, List<String> searchQueries, String testStrategy) {
    }

    private record PlanStep(int order, String title, String reason) {
    }

    private record RetrievalOutput(
            List<String> queries,
            Map<String, Integer> resultCountByQuery,
            List<CodeSearchResultResponse> results
    ) {
    }

    private record RetrievalAuditOutput(
            List<String> queries,
            Map<String, Integer> resultCountByQuery,
            int uniqueResultCount,
            List<Long> chunkIds
    ) {

        static RetrievalAuditOutput from(RetrievalOutput output) {
            return new RetrievalAuditOutput(
                    output.queries(),
                    output.resultCountByQuery(),
                    output.results().size(),
                    output.results().stream().map(CodeSearchResultResponse::chunkId).toList()
            );
        }
    }

    private record PatchGenerationPrompt(
            String taskTitle,
            String taskDescription,
            String taskType,
            String mode,
            int retrievedChunkCount,
            List<CoderContextCandidate> retrievedContext
    ) {

        static PatchGenerationPrompt from(AgentTask task, RetrievalOutput retrieval) {
            return new PatchGenerationPrompt(
                    task.getTitle(),
                    task.getDescription(),
                    task.getTaskType().name(),
                    "SPRING_RECIPE_CODER_WITH_RETRIEVAL_PLAN_FALLBACK",
                    retrieval.results().size(),
                    retrieval.results().stream().limit(8).map(CoderContextCandidate::from).toList()
            );
        }
    }

    private record CoderContextCandidate(
            Long chunkId,
            String filePath,
            String chunkType,
            String symbolType,
            String qualifiedName,
            Integer startLine,
            Integer endLine,
            String summary,
            String preview
    ) {

        static CoderContextCandidate from(CodeSearchResultResponse result) {
            return new CoderContextCandidate(
                    result.chunkId(),
                    result.filePath(),
                    result.chunkType() == null ? null : result.chunkType().name(),
                    result.symbolType() == null ? null : result.symbolType().name(),
                    result.qualifiedName(),
                    result.startLine(),
                    result.endLine(),
                    result.summary(),
                    result.preview()
            );
        }
    }

    private record PatchOutput(
            Long patchId,
            String status,
            String baseBranch,
            String targetBranch,
            String summary,
            String generationMode,
            String generationProvider,
            String generationModel
    ) {

        static PatchOutput from(PatchRecord patch) {
            return new PatchOutput(
                    patch.getId(),
                    patch.getStatus().name(),
                    patch.getBaseBranch(),
                    patch.getTargetBranch(),
                    patch.getSummary(),
                    patch.getGenerationMode(),
                    patch.getGenerationProvider(),
                    patch.getGenerationModel()
            );
        }
    }

    private record SandboxWorkspaceOutput(
            String runRoot,
            String sourcePath,
            String patchPath
    ) {

        static SandboxWorkspaceOutput from(SandboxTestService.SandboxWorkspace workspace) {
            return new SandboxWorkspaceOutput(
                    workspace.runRoot().toString(),
                    workspace.sourcePath().toString(),
                    workspace.patchPath().toString()
            );
        }
    }

    private record SandboxCommandOutput(
            String command,
            Integer exitCode,
            boolean timedOut,
            Integer durationMs,
            String logExcerpt,
            String logPath
    ) {

        static SandboxCommandOutput from(SandboxTestService.CommandResult result) {
            return new SandboxCommandOutput(
                    result.command(),
                    result.exitCode(),
                    result.timedOut(),
                    result.durationMs(),
                    result.logExcerpt(),
                    result.logPath()
            );
        }
    }

    private record TestRunOutput(
            Long testRunId,
            Long patchId,
            String status,
            String command,
            Integer exitCode,
            Integer durationMs,
            String logExcerpt
    ) {

        static TestRunOutput from(TestRun testRun) {
            return new TestRunOutput(
                    testRun.getId(),
                    testRun.getPatch().getId(),
                    testRun.getStatus().name(),
                    testRun.getCommand(),
                    testRun.getExitCode(),
                    testRun.getDurationMs(),
                    testRun.getLogExcerpt()
            );
        }
    }

    private record RunExecution(AgentRun run, PatchRecord patch) {
    }

    private record PatchValidation(TestRun testRun, boolean patchApplicationFailed, boolean cancelled) {
    }
}
