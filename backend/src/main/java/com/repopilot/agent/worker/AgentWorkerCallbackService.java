package com.repopilot.agent.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.dto.AgentStepResponse;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.agent.service.PatchDiffSafetyService;
import com.repopilot.agent.service.PatchRiskReviewService;
import com.repopilot.common.ApiException;
import com.repopilot.modelcall.dto.ModelCallLogResponse;
import com.repopilot.modelcall.service.ModelCallLogService;
import com.repopilot.notification.service.TaskStreamService;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.dto.PatchRecordResponse;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
import com.repopilot.sandbox.service.SandboxTestService;
import com.repopilot.toolcall.dto.ToolCallLogResponse;
import com.repopilot.toolcall.service.ToolCallLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentWorkerCallbackService {

    private final AgentWorkerTokenGuard tokenGuard;
    private final AgentRunRepository agentRunRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentStepRepository agentStepRepository;
    private final PatchRecordRepository patchRecordRepository;
    private final PatchDiffSafetyService patchDiffSafetyService;
    private final SandboxTestService sandboxTestService;
    private final TestRunRepository testRunRepository;
    private final PatchRiskReviewService patchRiskReviewService;
    private final ToolCallLogService toolCallLogService;
    private final ModelCallLogService modelCallLogService;
    private final TaskStreamService taskStreamService;
    private final ObjectMapper objectMapper;

    public AgentWorkerCallbackService(
            AgentWorkerTokenGuard tokenGuard,
            AgentRunRepository agentRunRepository,
            AgentTaskRepository agentTaskRepository,
            AgentStepRepository agentStepRepository,
            PatchRecordRepository patchRecordRepository,
            PatchDiffSafetyService patchDiffSafetyService,
            SandboxTestService sandboxTestService,
            TestRunRepository testRunRepository,
            PatchRiskReviewService patchRiskReviewService,
            ToolCallLogService toolCallLogService,
            ModelCallLogService modelCallLogService,
            TaskStreamService taskStreamService,
            ObjectMapper objectMapper
    ) {
        this.tokenGuard = tokenGuard;
        this.agentRunRepository = agentRunRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.agentStepRepository = agentStepRepository;
        this.patchRecordRepository = patchRecordRepository;
        this.patchDiffSafetyService = patchDiffSafetyService;
        this.sandboxTestService = sandboxTestService;
        this.testRunRepository = testRunRepository;
        this.patchRiskReviewService = patchRiskReviewService;
        this.toolCallLogService = toolCallLogService;
        this.modelCallLogService = modelCallLogService;
        this.taskStreamService = taskStreamService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentStepResponse recordStep(Long runId, String callbackToken, AgentWorkerStepRecordRequest request) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
        AgentStep step = agentStepRepository.save(new AgentStep(
                run,
                request.stepName(),
                request.status(),
                json(request.input(), "{}"),
                request.output() == null || request.output().isNull() ? null : json(request.output(), null),
                request.errorMessage()
        ));
        taskStreamService.publishStepRecorded(run.getAgentTask(), run, step);
        return AgentStepResponse.from(step);
    }

    @Transactional
    public ToolCallLogResponse recordToolCall(
            Long runId,
            String callbackToken,
            AgentWorkerToolCallRecordRequest request
    ) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = requireRun(runId);
        return ToolCallLogResponse.from(toolCallLogService.recordExternal(
                run,
                request.toolName(),
                request.input(),
                request.output(),
                request.status(),
                request.durationMs(),
                request.errorMessage(),
                request.startedAt(),
                request.finishedAt()
        ));
    }

    @Transactional
    public ModelCallLogResponse recordModelCall(
            Long runId,
            String callbackToken,
            AgentWorkerModelCallRecordRequest request
    ) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = requireRun(runId);
        return ModelCallLogResponse.from(modelCallLogService.recordExternal(
                run,
                request.stepName(),
                request.modelProvider(),
                request.modelName(),
                request.prompt(),
                request.response(),
                request.status(),
                request.promptTokens(),
                request.completionTokens(),
                request.totalTokens(),
                request.durationMs(),
                request.errorMessage(),
                request.startedAt(),
                request.finishedAt()
        ));
    }

    @Transactional
    public PatchRecordResponse recordPatch(
            Long runId,
            String callbackToken,
            AgentWorkerPatchRecordRequest request
    ) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = requireRun(runId);
        AgentTask task = run.getAgentTask();
        PatchRecord patch = patchRecordRepository.save(new PatchRecord(
                task,
                run,
                blankToDefault(request.baseBranch(), task.getProject().getDefaultBranch()),
                blankToDefault(request.targetBranch(), "repopilot/task-" + task.getId()),
                request.diffContent(),
                request.summary(),
                request.generationMode(),
                request.generationProvider(),
                request.generationModel()
        ));
        return PatchRecordResponse.from(patch);
    }

    @Transactional
    public AgentWorkerPatchSafetyResponse validatePatchSafety(Long runId, Long patchId, String callbackToken) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = requireRun(runId);
        PatchRecord patch = requirePatchForRun(run, patchId);
        PatchDiffSafetyService.PatchDiffSafetyReport report = patchDiffSafetyService.review(patch);
        AgentStepStatus stepStatus = report.safe() ? AgentStepStatus.SUCCESS : AgentStepStatus.FAILED;
        AgentStep step = agentStepRepository.save(new AgentStep(
                run,
                "validate_patch_safety",
                stepStatus,
                jsonValue(workerPatchSafetyInput(patch), "{}"),
                jsonValue(report, null),
                report.safe() ? null : "补丁 diff 未通过安全预检"
        ));
        taskStreamService.publishStepRecorded(run.getAgentTask(), run, step);
        return AgentWorkerPatchSafetyResponse.from(
                patch.getId(),
                patch.getAgentTask().getId(),
                run.getId(),
                report,
                step.getId(),
                stepStatus
        );
    }

    @Transactional
    public AgentWorkerPatchSandboxResponse runPatchSandboxTests(Long runId, Long patchId, String callbackToken) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = requireRun(runId);
        PatchRecord patch = requirePatchForRun(run, patchId);
        requireSafetyStepPassed(run.getId(), patch.getId());

        SandboxTestService.SandboxWorkspace workspace = toolCallLogService.record(
                run,
                "prepare_sandbox",
                java.util.Map.of("runId", run.getId(), "patchId", patch.getId(), "source", "agent-worker"),
                () -> sandboxTestService.prepareWorkspace(run, patch),
                AgentWorkerPatchSandboxResponse.SandboxWorkspaceOutput::from
        );
        SandboxTestService.CommandResult applyResult = toolCallLogService.record(
                run,
                "apply_patch",
                java.util.Map.of("patchId", patch.getId(), "patchPath", workspace.patchPath().toString(), "source", "agent-worker"),
                () -> sandboxTestService.applyPatch(workspace),
                AgentWorkerPatchSandboxResponse.SandboxCommandOutput::from
        );
        AgentStepStatus applyStepStatus = applyResult.success() ? AgentStepStatus.SUCCESS : AgentStepStatus.FAILED;
        AgentStep applyStep = saveWorkerStep(
                run,
                "apply_patch",
                applyStepStatus,
                java.util.Map.of("patchId", patch.getId(), "patchPath", workspace.patchPath().toString(), "source", "agent-worker"),
                AgentWorkerPatchSandboxResponse.SandboxCommandOutput.from(applyResult),
                applyResult.success() ? null : "补丁在沙箱中应用失败"
        );
        if (!applyResult.success()) {
            return AgentWorkerPatchSandboxResponse.applyFailed(
                    patch.getId(),
                    patch.getAgentTask().getId(),
                    run.getId(),
                    patch.getStatus(),
                    applyStep.getId(),
                    applyStepStatus,
                    applyResult
            );
        }

        patch.markApplied();
        patchRecordRepository.save(patch);
        TestRun testRun = toolCallLogService.record(
                run,
                "run_maven_test",
                java.util.Map.of("patchId", patch.getId(), "command", "mvn -q test", "source", "agent-worker"),
                () -> sandboxTestService.runMavenTest(run, patch, workspace),
                AgentWorkerPatchSandboxResponse.TestRunOutput::from
        );
        AgentStepStatus testStepStatus = testRun.getStatus() == TestRunStatus.PASSED
                ? AgentStepStatus.SUCCESS
                : AgentStepStatus.FAILED;
        AgentStep testStep = saveWorkerStep(
                run,
                "run_tests",
                testStepStatus,
                java.util.Map.of("patchId", patch.getId(), "command", "mvn -q test", "source", "agent-worker"),
                AgentWorkerPatchSandboxResponse.TestRunOutput.from(testRun),
                testRun.getStatus() == TestRunStatus.PASSED ? null : "沙箱 Maven 测试失败"
        );
        return AgentWorkerPatchSandboxResponse.from(
                patch.getId(),
                patch.getAgentTask().getId(),
                run.getId(),
                patch.getStatus(),
                applyStep.getId(),
                applyStepStatus,
                applyResult,
                testStep.getId(),
                testStepStatus,
                testRun
        );
    }

    @Transactional
    public AgentWorkerPatchReviewResponse reviewPatch(Long runId, Long patchId, String callbackToken) {
        tokenGuard.requireValidToken(callbackToken);
        AgentRun run = requireRun(runId);
        PatchRecord patch = requirePatchForRun(run, patchId);
        TestRun testRun = requirePassedTestRun(patch);
        PatchRiskReviewService.PatchRiskReview review = modelCallLogService.record(
                run,
                "review_patch",
                java.util.Map.of(
                        "patchId", patch.getId(),
                        "testRunId", testRun.getId(),
                        "source", "agent-worker"
                ),
                () -> patchRiskReviewService.review(patch, testRun)
        );
        AgentStep step = saveWorkerStep(
                run,
                "review_patch",
                AgentStepStatus.SUCCESS,
                java.util.Map.of(
                        "patchId", patch.getId(),
                        "testRunId", testRun.getId(),
                        "source", "agent-worker"
                ),
                review,
                null
        );
        return AgentWorkerPatchReviewResponse.from(
                patch.getId(),
                patch.getAgentTask().getId(),
                run.getId(),
                testRun.getId(),
                review,
                step.getId(),
                step.getStatus()
        );
    }

    @Transactional
    public AgentWorkerRunStatusUpdateResponse updateStatus(
            Long runId,
            String callbackToken,
            AgentWorkerRunStatusUpdateRequest request
    ) {
        tokenGuard.requireValidToken(callbackToken);
        if (request.taskStatus() == null && request.runStatus() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AGENT_WORKER_STATUS_EMPTY",
                    "Agent Worker status update must include task_status or run_status"
            );
        }
        AgentRun run = requireRun(runId);
        AgentTask task = run.getAgentTask();
        if (request.taskStatus() != null) {
            task.setStatus(request.taskStatus());
        }
        if (request.runStatus() != null) {
            applyRunStatus(run, request.runStatus(), request.errorMessage());
        }
        AgentTask savedTask = agentTaskRepository.save(task);
        AgentRun savedRun = agentRunRepository.save(run);
        taskStreamService.publishTaskUpdated(savedTask, savedRun);
        if (request.completeStream()) {
            taskStreamService.publishStreamComplete(savedTask, savedRun, streamMessage(request));
        }
        return AgentWorkerRunStatusUpdateResponse.from(savedTask, savedRun, request.completeStream());
    }

    private AgentRun requireRun(Long runId) {
        return agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
    }

    private PatchRecord requirePatchForRun(AgentRun run, Long patchId) {
        PatchRecord patch = patchRecordRepository.findById(patchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PATCH_NOT_FOUND", "Patch not found"));
        if (!patch.getAgentRun().getId().equals(run.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PATCH_NOT_FOUND", "Patch not found for current run");
        }
        return patch;
    }

    private TestRun requirePassedTestRun(PatchRecord patch) {
        if (patch.getStatus() != PatchStatus.APPLIED && patch.getStatus() != PatchStatus.APPROVED) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PATCH_TEST_NOT_PASSED",
                    "Worker patch must be applied and tested before review"
            );
        }
        TestRun testRun = testRunRepository.findFirstByPatchIdOrderByCreatedAtDesc(patch.getId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "PATCH_TEST_NOT_PASSED",
                        "Worker patch must pass sandbox tests before review"
                ));
        if (testRun.getStatus() != TestRunStatus.PASSED) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PATCH_TEST_NOT_PASSED",
                    "Worker patch must pass sandbox tests before review"
            );
        }
        return testRun;
    }

    private void requireSafetyStepPassed(Long runId, Long patchId) {
        boolean passed = agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(runId)
                .stream()
                .filter(step -> "validate_patch_safety".equals(step.getStepName()))
                .filter(step -> step.getStatus() == AgentStepStatus.SUCCESS)
                .anyMatch(step -> stepInputPatchId(step).equals(patchId));
        if (!passed) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PATCH_SAFETY_NOT_PASSED",
                    "Worker patch must pass diff safety before sandbox tests"
            );
        }
    }

    private Long stepInputPatchId(AgentStep step) {
        try {
            JsonNode node = objectMapper.readTree(step.getInputJson());
            JsonNode patchId = node.path("patchId");
            return patchId.canConvertToLong() ? patchId.asLong() : -1L;
        } catch (Exception exception) {
            return -1L;
        }
    }

    private AgentStep saveWorkerStep(
            AgentRun run,
            String stepName,
            AgentStepStatus status,
            Object input,
            Object output,
            String errorMessage
    ) {
        AgentStep step = agentStepRepository.save(new AgentStep(
                run,
                stepName,
                status,
                jsonValue(input, "{}"),
                jsonValue(output, null),
                errorMessage
        ));
        taskStreamService.publishStepRecorded(run.getAgentTask(), run, step);
        return step;
    }

    private void applyRunStatus(AgentRun run, AgentRunStatus status, String errorMessage) {
        switch (status) {
            case RUNNING -> {
            }
            case SUCCESS -> run.markSuccess();
            case FAILED -> run.markFailed(blankToDefault(errorMessage, "Agent Worker reported run failure"));
            case CANCELLED -> run.markCancelled(blankToDefault(errorMessage, "Agent Worker reported run cancellation"));
        }
    }

    private String streamMessage(AgentWorkerRunStatusUpdateRequest request) {
        return blankToDefault(request.streamMessage(), "Agent Worker 状态回写已完成");
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String json(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_WORKER_STEP_JSON_FAILED", exception.getOriginalMessage());
        }
    }

    private String jsonValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_WORKER_CALLBACK_JSON_FAILED", exception.getOriginalMessage());
        }
    }

    private Object workerPatchSafetyInput(PatchRecord patch) {
        return java.util.Map.of(
                "patchId", patch.getId(),
                "source", "agent-worker"
        );
    }
}
