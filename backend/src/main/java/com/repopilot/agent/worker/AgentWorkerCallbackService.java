package com.repopilot.agent.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.dto.AgentStepResponse;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.common.ApiException;
import com.repopilot.notification.service.TaskStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentWorkerCallbackService {

    private final AgentWorkerProperties properties;
    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;
    private final TaskStreamService taskStreamService;
    private final ObjectMapper objectMapper;

    public AgentWorkerCallbackService(
            AgentWorkerProperties properties,
            AgentRunRepository agentRunRepository,
            AgentStepRepository agentStepRepository,
            TaskStreamService taskStreamService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.agentRunRepository = agentRunRepository;
        this.agentStepRepository = agentStepRepository;
        this.taskStreamService = taskStreamService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentStepResponse recordStep(Long runId, String callbackToken, AgentWorkerStepRecordRequest request) {
        requireValidToken(callbackToken);
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

    private void requireValidToken(String callbackToken) {
        String expected = properties.getCallbackToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_WORKER_CALLBACK_DISABLED",
                    "Agent Worker callback token is not configured"
            );
        }
        if (callbackToken == null || callbackToken.isBlank() || !constantTimeEquals(expected, callbackToken)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_WORKER_CALLBACK_FORBIDDEN",
                    "Agent Worker callback token is invalid"
            );
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
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
