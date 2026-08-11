package com.repopilot.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class McpToolSettingsControllerIntegrationTest {

    private static final StubMcpToolServer STUB_SERVER = StubMcpToolServer.start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private String email;

    @DynamicPropertySource
    static void mcpToolServerProperties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.mcp-tool-server.base-url", STUB_SERVER::baseUrl);
        registry.add("repopilot.mcp-tool-server.timeout-seconds", () -> "2");
    }

    @BeforeEach
    void setUp() {
        email = "mcp-tool-settings-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    @AfterAll
    static void stopStubServer() {
        STUB_SERVER.stop();
    }

    @Test
    void mcpToolSettingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/settings/mcp-tools"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mcpToolSettingsReturnCatalogReadinessAndToolSummary() throws Exception {
        String token = register();

        MvcResult result = mockMvc.perform(get("/api/settings/mcp-tools")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("REPOPILOT_MCP_TOOL_SERVER"))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.baseUrl").value(STUB_SERVER.baseUrl()))
                .andExpect(jsonPath("$.data.healthAvailable").value(true))
                .andExpect(jsonPath("$.data.healthStatus").value("UP"))
                .andExpect(jsonPath("$.data.serviceName").value("RepoPilot MCP 工具目录服务"))
                .andExpect(jsonPath("$.data.protocolVersion").value("REPOPILOT_MCP_CONTRACT_V1"))
                .andExpect(jsonPath("$.data.toolCount").value(5))
                .andExpect(jsonPath("$.data.readToolCount").value(3))
                .andExpect(jsonPath("$.data.writeToolCount").value(2))
                .andExpect(jsonPath("$.data.approvalRequiredToolCount").value(2))
                .andExpect(jsonPath("$.data.requiredToolsPresent").value(true))
                .andExpect(jsonPath("$.data.missingRequiredTools").isEmpty())
                .andExpect(jsonPath("$.data.missingRequirements").isEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(responseBody).path("data");
        JsonNode checks = data.path("checks");
        assertThat(checks).extracting(node -> node.path("code").asText())
                .contains("MCP_HEALTH", "MCP_CATALOG", "MCP_PROTOCOL", "MCP_REQUIRED_TOOLS", "MCP_APPROVAL_GATE");
        assertThat(createPullRequestRequiresApproval(data.path("tools"))).isTrue();
        JsonNode readFileTool = tool(data.path("tools"), "read_file");
        assertThat(readFileTool.path("description").asText()).contains("UTF-8");
        assertThat(readFileTool.path("backendBridge").asText())
                .isEqualTo("backend:/api/internal/agent-worker/runs/{runId}/project/file");
        assertThat(readFileTool.path("safetyRules")).extracting(node -> node.asText())
                .contains("禁止绝对路径、.. 路径穿越和 .git 内部路径。");
        JsonNode pathArgument = argument(readFileTool, "path");
        assertThat(pathArgument.path("type").asText()).isEqualTo("string");
        assertThat(pathArgument.path("required").asBoolean()).isTrue();
        assertThat(pathArgument.path("description").asText()).contains("相对文件路径");
        JsonNode createPullRequestTool = tool(data.path("tools"), "create_pull_request");
        assertThat(createPullRequestTool.path("backendBridge").asText()).isEqualTo("backend:GitHubPullRequestService");
        assertThat(argument(createPullRequestTool, "approvedByHuman").path("defaultValue").asBoolean()).isFalse();
        assertThat(responseBody).doesNotContain("secret");
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "MCP Tool Settings"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private boolean createPullRequestRequiresApproval(JsonNode tools) {
        for (JsonNode tool : tools) {
            if ("create_pull_request".equals(tool.path("name").asText())) {
                return tool.path("approvalRequired").asBoolean(false);
            }
        }
        return false;
    }

    private JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("未找到 MCP 工具：" + name);
    }

    private JsonNode argument(JsonNode tool, String name) {
        for (JsonNode argument : tool.path("arguments")) {
            if (name.equals(argument.path("name").asText())) {
                return argument;
            }
        }
        throw new AssertionError("未找到 MCP 工具参数：" + name);
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
                throw new IllegalStateException("无法启动 MCP 工具目录测试 stub", exception);
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
                        "toolCount": 5,
                        "tools": [
                          {
                            "name": "list_project_files",
                            "title": "列出项目文件树",
                            "category": "仓库读取",
                            "description": "列出任务作用域内项目工作区的文件树。",
                            "accessMode": "READ",
                            "mvp": true,
                            "auditRequired": true,
                            "approvalRequired": false,
                            "backendBridge": "backend:/api/internal/agent-worker/runs/{runId}/project/files",
                            "arguments": [
                              {"name": "runId", "type": "number", "required": true, "description": "Agent run ID。", "defaultValue": null, "allowedValues": []},
                              {"name": "maxDepth", "type": "number", "required": false, "description": "最大遍历深度。", "defaultValue": 6, "allowedValues": []}
                            ],
                            "safetyRules": ["路径必须是项目工作区内相对路径。"]
                          },
                          {
                            "name": "read_file",
                            "title": "读取项目文件",
                            "category": "仓库读取",
                            "description": "读取任务作用域项目工作区内的单个 UTF-8 文件。",
                            "accessMode": "READ",
                            "mvp": true,
                            "auditRequired": true,
                            "approvalRequired": false,
                            "backendBridge": "backend:/api/internal/agent-worker/runs/{runId}/project/file",
                            "arguments": [
                              {"name": "runId", "type": "number", "required": true, "description": "Agent run ID。", "defaultValue": null, "allowedValues": []},
                              {"name": "path", "type": "string", "required": true, "description": "项目工作区内相对文件路径。", "defaultValue": null, "allowedValues": []}
                            ],
                            "safetyRules": ["路径必须是项目工作区内相对路径。", "禁止绝对路径、.. 路径穿越和 .git 内部路径。"]
                          },
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
                              {"name": "command", "type": "string", "required": false, "description": "固定为 mvn -q test。", "defaultValue": "mvn -q test", "allowedValues": []},
                              {"name": "approvedByHuman", "type": "boolean", "required": false, "description": "是否允许执行写型沙箱动作。", "defaultValue": false, "allowedValues": []}
                            ],
                            "safetyRules": ["命令只能在 Docker 沙箱或隔离工作区执行。"]
                          },
                          {
                            "name": "create_pull_request",
                            "title": "创建 GitHub PR",
                            "category": "GitHub 集成",
                            "description": "推送目标分支并通过 GitHub API 创建 PR。",
                            "accessMode": "WRITE",
                            "mvp": true,
                            "auditRequired": true,
                            "approvalRequired": true,
                            "backendBridge": "backend:GitHubPullRequestService",
                            "arguments": [
                              {"name": "taskId", "type": "number", "required": true, "description": "Agent task ID。", "defaultValue": null, "allowedValues": []},
                              {"name": "title", "type": "string", "required": true, "description": "PR 标题。", "defaultValue": null, "allowedValues": []},
                              {"name": "body", "type": "string", "required": true, "description": "PR 描述。", "defaultValue": null, "allowedValues": []},
                              {"name": "approvedByHuman", "type": "boolean", "required": false, "description": "是否已经过人工审批。", "defaultValue": false, "allowedValues": []}
                            ],
                            "safetyRules": ["必须有已审批 patch、通过的沙箱测试和 PR preflight。", "GitHub token 不得进入工具审计明文。"]
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
