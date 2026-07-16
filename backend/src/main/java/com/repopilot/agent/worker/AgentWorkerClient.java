package com.repopilot.agent.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.common.ApiException;
import com.repopilot.project.domain.Project;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentWorkerClient implements AgentWorkerGateway {

    private final AgentWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public AgentWorkerClient(AgentWorkerProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                        .build()
        );
    }

    AgentWorkerClient(AgentWorkerProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public boolean isPrimaryExecutionReady() {
        return properties.isEnabled()
                && properties.getCallbackToken() != null
                && !properties.getCallbackToken().isBlank();
    }

    @Override
    public AgentWorkerStartResult startRun(AgentRun run) {
        AgentTask task = run.getAgentTask();
        Project project = task.getProject();
        AgentWorkerStartRequest request = AgentWorkerStartRequest.from(task, project);
        String body = json(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(startUri(run))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw startFailed("Agent Worker 启动请求失败：" + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw startFailed("Agent Worker 启动请求被中断", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw startFailed(
                    "Agent Worker 返回非成功状态：" + response.statusCode() + responseBodyMessage(response.body()),
                    null
            );
        }
        try {
            AgentWorkerStartResult result = objectMapper.readValue(response.body(), AgentWorkerStartResult.class);
            validateResult(run, result);
            return result;
        } catch (JsonProcessingException exception) {
            throw startFailed("Agent Worker 响应无法解析：" + exception.getOriginalMessage(), exception);
        }
    }

    private void validateResult(AgentRun run, AgentWorkerStartResult result) {
        if (!run.getId().equals(result.runId())) {
            throw startFailed("Agent Worker 响应 run_id 不匹配：" + result.runId(), null);
        }
        if (!result.accepted()) {
            throw startFailed("Agent Worker 未接受 run 启动请求", null);
        }
        if (!"QUEUED".equals(result.status())) {
            throw startFailed("Agent Worker 返回非 QUEUED 状态：" + result.status(), null);
        }
    }

    private URI startUri(AgentRun run) {
        String baseUrl = properties.getBaseUrl();
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + "/runs/" + run.getId() + "/start");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw startFailed("Agent Worker 请求无法序列化：" + exception.getOriginalMessage(), exception);
        }
    }

    private String responseBodyMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        String compactBody = responseBody.replaceAll("\\s+", " ").trim();
        if (compactBody.length() > 800) {
            compactBody = compactBody.substring(0, 800) + "...";
        }
        return "，响应：" + compactBody;
    }

    private ApiException startFailed(String message, Throwable cause) {
        ApiException exception = new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AGENT_WORKER_START_FAILED",
                message
        );
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private record AgentWorkerStartRequest(
            @JsonProperty("task_id") Long taskId,
            @JsonProperty("project_id") Long projectId,
            @JsonProperty("user_request") String userRequest,
            @JsonProperty("repo_path") String repoPath,
            @JsonProperty("base_branch") String baseBranch
    ) {

        static AgentWorkerStartRequest from(AgentTask task, Project project) {
            return new AgentWorkerStartRequest(
                    task.getId(),
                    project.getId(),
                    task.getTitle() + "\n\n" + task.getDescription(),
                    project.getLocalPath(),
                    project.getDefaultBranch()
            );
        }
    }
}
