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

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        JsonNode checks = data.path("checks");
        assertThat(checks).extracting(node -> node.path("code").asText())
                .contains("MCP_HEALTH", "MCP_CATALOG", "MCP_PROTOCOL", "MCP_REQUIRED_TOOLS", "MCP_APPROVAL_GATE");
        assertThat(createPullRequestRequiresApproval(data.path("tools"))).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret");
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
                          {"name":"list_project_files","title":"列出项目文件树","category":"仓库读取","accessMode":"READ","mvp":true,"auditRequired":true,"approvalRequired":false},
                          {"name":"read_file","title":"读取项目文件","category":"仓库读取","accessMode":"READ","mvp":true,"auditRequired":true,"approvalRequired":false},
                          {"name":"search_code","title":"检索代码上下文","category":"仓库读取","accessMode":"READ","mvp":true,"auditRequired":true,"approvalRequired":false},
                          {"name":"run_maven_test","title":"运行 Maven test","category":"构建测试","accessMode":"WRITE","mvp":true,"auditRequired":true,"approvalRequired":true},
                          {"name":"create_pull_request","title":"创建 GitHub PR","category":"GitHub 集成","accessMode":"WRITE","mvp":true,"auditRequired":true,"approvalRequired":true}
                        ]
                      },
                      "code": null,
                      "message": null
                    }
                    """;
        }
    }
}
