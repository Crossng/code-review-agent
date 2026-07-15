package com.repopilot.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.common.ApiException;
import com.repopilot.project.domain.Project;
import com.repopilot.user.domain.User;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentWorkerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void startRunPostsWorkerContractAndParsesResponse() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, Map.of(
                "run_id", 303,
                "accepted", true,
                "status", "QUEUED",
                "graph_nodes", List.of("load_task_context", "ensure_index", "plan_task")
        ), method, path, requestBody);
        AgentWorkerClient client = client(true);

        AgentWorkerStartResult result = client.startRun(run());

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/runs/303/start");
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.path("task_id").asLong()).isEqualTo(101L);
        assertThat(request.path("project_id").asLong()).isEqualTo(202L);
        assertThat(request.path("user_request").asText())
                .contains("给 User 模块新增分页查询接口")
                .contains("保持现有 Controller 风格");
        assertThat(request.path("repo_path").asText()).isEqualTo("/workspace/repos/202/source");
        assertThat(request.path("base_branch").asText()).isEqualTo("main");
        assertThat(result.runId()).isEqualTo(303L);
        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.graphNodes()).containsExactly("load_task_context", "ensure_index", "plan_task");
    }

    @Test
    void startRunTranslatesNonSuccessStatusToApiException() throws Exception {
        startServer(503, Map.of("error", "worker unavailable"), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        AgentWorkerClient client = client(true);

        assertThatThrownBy(() -> client.startRun(run()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Agent Worker 返回非成功状态：503")
                .extracting("code")
                .isEqualTo("AGENT_WORKER_START_FAILED");
    }

    @Test
    void startRunRejectsMismatchedWorkerResponseContract() throws Exception {
        startServer(200, Map.of(
                "run_id", 999,
                "accepted", true,
                "status", "QUEUED",
                "graph_nodes", List.of("load_task_context")
        ), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        AgentWorkerClient client = client(true);

        assertThatThrownBy(() -> client.startRun(run()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Agent Worker 响应 run_id 不匹配：999");
    }

    @Test
    void isEnabledReflectsConfiguration() throws Exception {
        startServer(200, Map.of(), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());

        assertThat(client(true).isEnabled()).isTrue();
        assertThat(client(false).isEnabled()).isFalse();
    }

    private void startServer(
            int statusCode,
            Map<String, Object> response,
            AtomicReference<String> method,
            AtomicReference<String> path,
            AtomicReference<String> requestBody
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    private AgentWorkerClient client(boolean enabled) {
        AgentWorkerProperties properties = new AgentWorkerProperties();
        properties.setEnabled(enabled);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeoutSeconds(3);
        return new AgentWorkerClient(properties, objectMapper, HttpClient.newHttpClient());
    }

    private AgentRun run() {
        User user = new User("worker@example.test", "hash", "Worker", "USER");
        setId(user, 1L);
        Project project = new Project(user, "file:///demo", "demo", "main");
        setId(project, 202L);
        project.setLocalPath("/workspace/repos/202/source");
        AgentTask task = new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "给 User 模块新增分页查询接口",
                "保持现有 Controller 风格，并补充测试。"
        );
        setId(task, 101L);
        AgentRun run = new AgentRun(task);
        setId(run, 303L);
        return run;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
