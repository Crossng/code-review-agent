package com.repopilot.toolcall.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import com.repopilot.toolcall.service.ToolCallLogService;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ToolCallLogControllerIntegrationTest {

    private static final StubMcpToolServer STUB_SERVER = StubMcpToolServer.start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private ToolCallLogRepository toolCallLogRepository;

    @Autowired
    private ToolCallLogService toolCallLogService;

    private String ownerEmail;
    private String otherEmail;
    private Long runId;
    private AgentTask task;
    private Project project;

    @DynamicPropertySource
    static void mcpToolServerProperties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.mcp-tool-server.base-url", STUB_SERVER::baseUrl);
        registry.add("repopilot.mcp-tool-server.timeout-seconds", () -> "2");
    }

    @BeforeEach
    void setUp() {
        ownerEmail = "tool-call-owner-" + UUID.randomUUID() + "@example.test";
        otherEmail = "tool-call-other-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() {
        if (runId != null) {
            toolCallLogRepository.deleteAll(toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(runId));
            if (task != null) {
                task.setCurrentRun(null);
                agentTaskRepository.save(task);
            }
            agentRunRepository.findById(runId).ifPresent(agentRunRepository::delete);
        }
        if (task != null && task.getId() != null) {
            agentTaskRepository.findById(task.getId()).ifPresent(agentTaskRepository::delete);
        }
        if (project != null && project.getId() != null) {
            projectRepository.findById(project.getId()).ifPresent(projectRepository::delete);
        }
        userRepository.findByEmail(ownerEmail).ifPresent(userRepository::delete);
        userRepository.findByEmail(otherEmail).ifPresent(userRepository::delete);
    }

    @AfterAll
    static void stopStubServer() {
        STUB_SERVER.stop();
    }

    @Test
    void listToolCallsReturnsAuditedCallsForRunOwnerOnly() throws Exception {
        String ownerToken = register(ownerEmail);
        String otherToken = register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        AgentRun run = createRun(owner);

        toolCallLogService.record(
                run,
                "search_code",
                Map.of("query", "UserService", "accessToken", "secret-value"),
                () -> Map.of("resultCount", 2)
        );
        toolCallLogService.record(
                run,
                "load_run_context",
                Map.of(),
                () -> Map.of(
                        "runId", run.getId(),
                        "retryAttemptCount", 1,
                        "retryAttempts", List.of(Map.of(
                                "attempt", 1,
                                "errorType", "BackendApiError",
                                "message", "Backend internal API failed with HTTP 503: temporary outage",
                                "retryable", true
                        ))
                )
        );
        assertThatThrownBy(() -> toolCallLogService.record(
                run,
                "run_maven_test",
                Map.of("command", "mvn -q test"),
                () -> {
                    throw new IllegalStateException("Maven failed");
                }
        )).isInstanceOf(IllegalStateException.class);

        MvcResult result = mockMvc.perform(get("/api/agent/runs/{runId}/tool-calls", runId)
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode calls = data(result);
        assertThat(calls).hasSize(3);
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("toolName").asText()).isEqualTo("search_code");
            assertThat(call.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(call.path("inputJson").asText()).contains("[REDACTED]").doesNotContain("secret-value");
            assertThat(call.path("outputJson").asText()).contains("resultCount");
            assertThat(call.path("retryAudit").isNull()).isTrue();
            assertThat(call.path("durationMs").asInt()).isGreaterThanOrEqualTo(0);
            JsonNode snapshot = jsonNode(call.path("mcpToolSnapshotJson").asText());
            assertThat(snapshot.path("protocolVersion").asText()).isEqualTo("REPOPILOT_MCP_CONTRACT_V1");
            assertThat(snapshot.path("toolFound").asBoolean()).isTrue();
            assertThat(snapshot.path("normalizedToolName").asText()).isEqualTo("search_code");
            assertThat(snapshot.path("category").asText()).isEqualTo("仓库读取");
            assertThat(snapshot.path("backendBridge").asText())
                    .isEqualTo("backend:/api/internal/agent-worker/runs/{runId}/project/search");
            assertThat(snapshot.path("arguments")).extracting(argument -> argument.path("name").asText())
                    .contains("query", "limit");
            assertThat(snapshot.path("safetyRules")).extracting(rule -> rule.asText())
                    .contains("查询词会进入工具审计，不能包含 token 或密钥。");
        });
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("toolName").asText()).isEqualTo("load_run_context");
            assertThat(call.path("retryAudit").path("attemptCount").asInt()).isEqualTo(1);
            assertThat(call.path("retryAudit").path("recovered").asBoolean()).isTrue();
            assertThat(call.path("retryAudit").path("firstFailureType").asText()).isEqualTo("BackendApiError");
            assertThat(call.path("retryAudit").path("firstFailureMessage").asText()).contains("HTTP 503");
            JsonNode snapshot = jsonNode(call.path("mcpToolSnapshotJson").asText());
            assertThat(snapshot.path("catalogAvailable").asBoolean()).isTrue();
            assertThat(snapshot.path("toolFound").asBoolean()).isFalse();
            assertThat(snapshot.path("reason").asText()).contains("未找到");
        });
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("toolName").asText()).isEqualTo("run_maven_test");
            assertThat(call.path("status").asText()).isEqualTo("FAILED");
            assertThat(call.path("errorMessage").asText()).isEqualTo("Maven failed");
            JsonNode snapshot = jsonNode(call.path("mcpToolSnapshotJson").asText());
            assertThat(snapshot.path("approvalRequired").asBoolean()).isTrue();
            assertThat(snapshot.path("backendBridge").asText()).isEqualTo("backend:SandboxTestService");
        });

        mockMvc.perform(get("/api/agent/runs/{runId}/tool-calls", runId)
                        .header(AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_FORBIDDEN"));
    }

    private AgentRun createRun(User owner) {
        project = projectRepository.save(new Project(owner, "file:///tmp/demo.git", "example/demo", "main"));
        task = agentTaskRepository.save(new AgentTask(
                project,
                owner,
                AgentTaskType.FEATURE,
                "Trace tool calls",
                "Verify tool call auditing"
        ));
        AgentRun run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        agentTaskRepository.save(task);
        runId = run.getId();
        return run;
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Trace User"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return data(result).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode jsonNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException exception) {
            throw new AssertionError("JSON 解析失败", exception);
        }
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static final class StubMcpToolServer {

        private final HttpServer server;
        private final String baseUrl;

        private StubMcpToolServer(HttpServer server, String baseUrl) {
            this.server = server;
            this.baseUrl = baseUrl;
        }

        static StubMcpToolServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/actuator/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
                server.createContext("/api/mcp/tools", exchange -> respond(exchange, 200, toolsResponse()));
                server.start();
                return new StubMcpToolServer(server, "http://127.0.0.1:" + server.getAddress().getPort());
            } catch (IOException exception) {
                throw new IllegalStateException("无法启动工具调用 MCP stub", exception);
            }
        }

        String baseUrl() {
            return baseUrl;
        }

        void stop() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private static String toolsResponse() {
            return """
                    {
                      "success": true,
                      "data": {
                        "service": "RepoPilot MCP 工具目录服务",
                        "protocolVersion": "REPOPILOT_MCP_CONTRACT_V1",
                        "toolCount": 2,
                        "tools": [
                          {
                            "name": "search_code",
                            "title": "检索代码上下文",
                            "category": "仓库读取",
                            "description": "按关键词检索任务作用域项目的代码 chunk。",
                            "accessMode": "READ",
                            "mvp": true,
                            "auditRequired": true,
                            "approvalRequired": false,
                            "backendBridge": "backend:/api/internal/agent-worker/runs/{runId}/project/search",
                            "arguments": [
                              {"name": "runId", "type": "number", "required": true, "description": "Agent run ID。", "defaultValue": null, "allowedValues": []},
                              {"name": "query", "type": "string", "required": true, "description": "检索关键词。", "defaultValue": null, "allowedValues": []},
                              {"name": "limit", "type": "number", "required": false, "description": "返回结果数量。", "defaultValue": 8, "allowedValues": []}
                            ],
                            "safetyRules": ["查询词会进入工具审计，不能包含 token 或密钥。"]
                          },
                          {
                            "name": "run_maven_test",
                            "title": "运行 Maven test",
                            "category": "构建测试",
                            "description": "在 Docker 沙箱中执行 Maven 测试。",
                            "accessMode": "WRITE",
                            "mvp": true,
                            "auditRequired": true,
                            "approvalRequired": true,
                            "backendBridge": "backend:SandboxTestService",
                            "arguments": [
                              {"name": "runId", "type": "number", "required": true, "description": "Agent run ID。", "defaultValue": null, "allowedValues": []},
                              {"name": "command", "type": "string", "required": false, "description": "固定为 mvn -q test。", "defaultValue": "mvn -q test", "allowedValues": []}
                            ],
                            "safetyRules": ["命令只能在 Docker 沙箱或隔离工作区执行。"]
                          }
                        ]
                      },
                      "code": null,
                      "message": null
                    }
                    """;
        }
    }
}
