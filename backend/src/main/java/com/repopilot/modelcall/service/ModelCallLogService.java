package com.repopilot.modelcall.service;

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
import com.repopilot.modelcall.domain.ModelCallLog;
import com.repopilot.modelcall.domain.ModelCallStatus;
import com.repopilot.modelcall.repository.ModelCallLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ModelCallLogService {

    private static final int JSON_LIMIT = 16_000;
    private static final String DEFAULT_PROVIDER = "LOCAL_PLACEHOLDER";
    private static final String DEFAULT_MODEL = "deterministic-mvp";

    private final ModelCallLogRepository modelCallLogRepository;
    private final ObjectMapper objectMapper;

    public ModelCallLogService(ModelCallLogRepository modelCallLogRepository, ObjectMapper objectMapper) {
        this.modelCallLogRepository = modelCallLogRepository;
        this.objectMapper = objectMapper;
    }

    public <T> T record(AgentRun run, String stepName, Object prompt, ModelAction<T> action) {
        return record(run, stepName, DEFAULT_PROVIDER, DEFAULT_MODEL, prompt, action, output -> output);
    }

    public <T> T record(
            AgentRun run,
            String stepName,
            Object prompt,
            ModelAction<T> action,
            Function<T, Object> responseSummary
    ) {
        return record(run, stepName, DEFAULT_PROVIDER, DEFAULT_MODEL, prompt, action, responseSummary);
    }

    public <T> T record(
            AgentRun run,
            String stepName,
            String modelProvider,
            String modelName,
            Object prompt,
            ModelAction<T> action,
            Function<T, Object> responseSummary
    ) {
        Instant startedAt = Instant.now();
        String promptJson = prompt == null ? null : json(prompt);
        try {
            T output = action.execute();
            String responseJson = json(responseSummary.apply(output));
            save(
                    run,
                    stepName,
                    modelProvider,
                    modelName,
                    promptJson,
                    responseJson,
                    ModelCallStatus.SUCCESS,
                    null,
                    startedAt
            );
            return output;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            save(
                    run,
                    stepName,
                    modelProvider,
                    modelName,
                    promptJson,
                    json(Map.of("error", message)),
                    ModelCallStatus.FAILED,
                    message,
                    startedAt
            );
            throw exception;
        }
    }

    private void save(
            AgentRun run,
            String stepName,
            String modelProvider,
            String modelName,
            String promptJson,
            String responseJson,
            ModelCallStatus status,
            String errorMessage,
            Instant startedAt
    ) {
        Instant finishedAt = Instant.now();
        int promptTokens = estimateTokens(promptJson);
        int completionTokens = estimateTokens(responseJson);
        modelCallLogRepository.save(new ModelCallLog(
                run,
                stepName,
                modelProvider,
                modelName,
                promptJson,
                responseJson,
                status,
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0, Duration.between(startedAt, finishedAt).toMillis())),
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
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MODEL_CALL_JSON_FAILED", exception.getMessage());
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
                || normalized.contains("authorization")
                || normalized.contains("api_key")
                || normalized.contains("apikey");
    }

    private int estimateTokens(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(json.length() / 4.0));
    }

    @FunctionalInterface
    public interface ModelAction<T> {
        T execute();
    }
}
