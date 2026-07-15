package com.repopilot.toolcall.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.common.ApiException;
import com.repopilot.toolcall.domain.ToolCallLog;
import com.repopilot.toolcall.domain.ToolCallStatus;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ToolCallLogService {

    private static final int JSON_LIMIT = 16_000;

    private final ToolCallLogRepository toolCallLogRepository;
    private final ObjectMapper objectMapper;

    public ToolCallLogService(ToolCallLogRepository toolCallLogRepository, ObjectMapper objectMapper) {
        this.toolCallLogRepository = toolCallLogRepository;
        this.objectMapper = objectMapper;
    }

    public <T> T record(AgentRun run, String toolName, Object input, ToolAction<T> action) {
        return record(run, toolName, input, action, output -> output);
    }

    public <T> T record(
            AgentRun run,
            String toolName,
            Object input,
            ToolAction<T> action,
            Function<T, Object> outputSummary
    ) {
        Instant startedAt = Instant.now();
        try {
            T output = action.execute();
            save(run, toolName, input, outputSummary.apply(output), ToolCallStatus.SUCCESS, null, startedAt);
            return output;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            save(run, toolName, input, Map.of("error", message), ToolCallStatus.FAILED, message, startedAt);
            throw exception;
        }
    }

    private void save(
            AgentRun run,
            String toolName,
            Object input,
            Object output,
            ToolCallStatus status,
            String errorMessage,
            Instant startedAt
    ) {
        Instant finishedAt = Instant.now();
        long duration = Duration.between(startedAt, finishedAt).toMillis();
        toolCallLogRepository.save(new ToolCallLog(
                run,
                toolName,
                input == null ? null : json(input),
                output == null ? null : json(output),
                status,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0, duration)),
                errorMessage,
                startedAt,
                finishedAt
        ));
    }

    private String json(Object value) {
        try {
            JsonNode node = objectMapper.valueToTree(value);
            sanitize(node);
            String json = objectMapper.writeValueAsString(node);
            if (json.length() <= JSON_LIMIT) {
                return json;
            }
            return objectMapper.writeValueAsString(Map.of(
                    "truncated", true,
                    "preview", json.substring(0, JSON_LIMIT)
            ));
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TOOL_CALL_JSON_FAILED", exception.getMessage());
        }
    }

    private void sanitize(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    object.put(field.getKey(), "[REDACTED]");
                } else {
                    sanitize(field.getValue());
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(this::sanitize);
        }
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase();
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("authorization");
    }

    @FunctionalInterface
    public interface ToolAction<T> {
        T execute();
    }
}
