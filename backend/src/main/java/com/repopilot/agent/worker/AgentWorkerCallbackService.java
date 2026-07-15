package com.repopilot.agent.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.dto.AgentStepResponse;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.common.ApiException;
import com.repopilot.notification.service.TaskStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentWorkerCallbackService {

    private final AgentWorkerTokenGuard tokenGuard;
    private final AgentRunRepository agentRunRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentStepRepository agentStepRepository;
    private final TaskStreamService taskStreamService;
    private final ObjectMapper objectMapper;

    public AgentWorkerCallbackService(
            AgentWorkerTokenGuard tokenGuard,
            AgentRunRepository agentRunRepository,
            AgentTaskRepository agentTaskRepository,
            AgentStepRepository agentStepRepository,
            TaskStreamService taskStreamService,
            ObjectMapper objectMapper
    ) {
        this.tokenGuard = tokenGuard;
        this.agentRunRepository = agentRunRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.agentStepRepository = agentStepRepository;
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
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
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
}
