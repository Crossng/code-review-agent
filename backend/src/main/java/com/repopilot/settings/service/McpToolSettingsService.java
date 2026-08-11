package com.repopilot.settings.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.settings.dto.McpToolArgumentResponse;
import com.repopilot.settings.dto.McpToolSettingsCheckResponse;
import com.repopilot.settings.dto.McpToolSettingsResponse;
import com.repopilot.settings.dto.McpToolSummaryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class McpToolSettingsService {

    private static final String EXPECTED_PROTOCOL_VERSION = "REPOPILOT_MCP_CONTRACT_V1";
    private static final List<String> REQUIRED_TOOLS = List.of(
            "list_project_files",
            "read_file",
            "search_code",
            "run_maven_test",
            "create_pull_request"
    );

    private final String baseUrl;
    private final int timeoutSeconds;
    private final boolean healthCheckEnabled;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public McpToolSettingsService(
            @Value("${repopilot.mcp-tool-server.base-url:http://127.0.0.1:8095}") String baseUrl,
            @Value("${repopilot.mcp-tool-server.timeout-seconds:3}") int timeoutSeconds,
            @Value("${repopilot.mcp-tool-server.health-check-enabled:true}") boolean healthCheckEnabled,
            ObjectMapper objectMapper
    ) {
        this(
                baseUrl,
                timeoutSeconds,
                healthCheckEnabled,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build()
        );
    }

    McpToolSettingsService(
            String baseUrl,
            int timeoutSeconds,
            boolean healthCheckEnabled,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.healthCheckEnabled = healthCheckEnabled;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public McpToolSettingsResponse current() {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        HealthSnapshot health = healthCheckEnabled
                ? loadHealth(normalizedBaseUrl)
                : new HealthSnapshot(false, null, "健康检查已关闭。");
        CatalogSnapshot catalog = healthCheckEnabled && !health.available()
                ? CatalogSnapshot.failed("MCP 健康检查未通过，跳过工具目录读取。")
                : loadCatalog(normalizedBaseUrl);
        List<McpToolSettingsCheckResponse> checks = new ArrayList<>();
        checks.add(healthCheck(health));
        checks.add(catalogCheck(catalog));
        checks.add(protocolCheck(catalog));
        checks.add(requiredToolsCheck(catalog));
        checks.add(approvalGateCheck(catalog));

        List<String> missingRequirements = checks.stream()
                .filter(check -> "BLOCKED".equals(check.status()))
                .map(McpToolSettingsCheckResponse::code)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        boolean ready = missingRequirements.isEmpty();

        return new McpToolSettingsResponse(
                "REPOPILOT_MCP_TOOL_SERVER",
                ready,
                normalizedBaseUrl,
                healthCheckEnabled,
                health.available(),
                health.status(),
                catalog.serviceName(),
                catalog.protocolVersion(),
                catalog.toolCount(),
                catalog.mvpToolCount(),
                catalog.readToolCount(),
                catalog.writeToolCount(),
                catalog.auditRequiredToolCount(),
                catalog.approvalRequiredToolCount(),
                catalog.missingRequiredTools().isEmpty(),
                REQUIRED_TOOLS,
                catalog.missingRequiredTools(),
                catalog.categories(),
                catalog.tools(),
                missingRequirements,
                checks
        );
    }

    private HealthSnapshot loadHealth(String normalizedBaseUrl) {
        HttpResult result = getJson(normalizedBaseUrl + "/actuator/health");
        if (!result.successful()) {
            return new HealthSnapshot(false, null, result.message());
        }
        try {
            JsonNode body = objectMapper.readTree(result.body());
            String status = text(body, "status", null);
            return new HealthSnapshot("UP".equals(status), status, "健康检查返回 " + (status == null ? "UNKNOWN" : status) + "。");
        } catch (IOException exception) {
            return new HealthSnapshot(false, null, "健康检查响应无法解析：" + exception.getMessage());
        }
    }

    private CatalogSnapshot loadCatalog(String normalizedBaseUrl) {
        HttpResult result = getJson(normalizedBaseUrl + "/api/mcp/tools");
        if (!result.successful()) {
            return CatalogSnapshot.failed(result.message());
        }
        try {
            JsonNode root = objectMapper.readTree(result.body());
            if (!root.path("success").asBoolean(false)) {
                return CatalogSnapshot.failed(text(root, "message", "工具目录响应 success=false。"));
            }
            JsonNode data = root.path("data");
            List<McpToolSummaryResponse> tools = tools(data.path("tools"));
            Set<String> toolNames = new LinkedHashSet<>();
            for (McpToolSummaryResponse tool : tools) {
                toolNames.add(tool.name());
            }
            List<String> missingRequiredTools = REQUIRED_TOOLS.stream()
                    .filter(requiredTool -> !toolNames.contains(requiredTool))
                    .toList();
            List<String> categories = tools.stream()
                    .map(McpToolSummaryResponse::category)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
            int readToolCount = (int) tools.stream().filter(tool -> "READ".equals(tool.accessMode())).count();
            int writeToolCount = (int) tools.stream().filter(tool -> "WRITE".equals(tool.accessMode())).count();
            int mvpToolCount = (int) tools.stream().filter(McpToolSummaryResponse::mvp).count();
            int auditRequiredToolCount = (int) tools.stream().filter(McpToolSummaryResponse::auditRequired).count();
            int approvalRequiredToolCount = (int) tools.stream().filter(McpToolSummaryResponse::approvalRequired).count();
            int declaredToolCount = intValue(data, "toolCount", tools.size());
            return new CatalogSnapshot(
                    true,
                    null,
                    text(data, "service", null),
                    text(data, "protocolVersion", null),
                    declaredToolCount,
                    mvpToolCount,
                    readToolCount,
                    writeToolCount,
                    auditRequiredToolCount,
                    approvalRequiredToolCount,
                    missingRequiredTools,
                    categories,
                    tools
            );
        } catch (IOException exception) {
            return CatalogSnapshot.failed("工具目录响应无法解析：" + exception.getMessage());
        }
    }

    private List<McpToolSummaryResponse> tools(JsonNode toolsNode) {
        if (!toolsNode.isArray()) {
            return List.of();
        }
        List<McpToolSummaryResponse> tools = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            tools.add(new McpToolSummaryResponse(
                    text(tool, "name", ""),
                    text(tool, "title", ""),
                    text(tool, "category", ""),
                    text(tool, "description", ""),
                    text(tool, "accessMode", ""),
                    tool.path("mvp").asBoolean(false),
                    tool.path("auditRequired").asBoolean(false),
                    tool.path("approvalRequired").asBoolean(false),
                    text(tool, "backendBridge", ""),
                    arguments(tool.path("arguments")),
                    stringList(tool.path("safetyRules"))
            ));
        }
        tools.sort(Comparator.comparing(McpToolSummaryResponse::category)
                .thenComparing(McpToolSummaryResponse::name));
        return List.copyOf(tools);
    }

    private List<McpToolArgumentResponse> arguments(JsonNode argumentsNode) {
        if (!argumentsNode.isArray()) {
            return List.of();
        }
        List<McpToolArgumentResponse> arguments = new ArrayList<>();
        for (JsonNode argument : argumentsNode) {
            arguments.add(new McpToolArgumentResponse(
                    text(argument, "name", ""),
                    text(argument, "type", ""),
                    argument.path("required").asBoolean(false),
                    text(argument, "description", ""),
                    defaultValue(argument),
                    stringList(argument.path("allowedValues"))
            ));
        }
        return List.copyOf(arguments);
    }

    private JsonNode defaultValue(JsonNode node) {
        JsonNode value = node.get("defaultValue");
        return value == null || value.isNull() ? null : value;
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isTextual()) {
                values.add(value.asText());
            }
        }
        return List.copyOf(values);
    }

    private HttpResult getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new HttpResult(false, response.body(), "HTTP " + response.statusCode() + "：" + compact(response.body()));
            }
            return new HttpResult(true, response.body(), "请求成功。");
        } catch (IOException exception) {
            return new HttpResult(false, "", "无法连接 MCP 工具目录服务：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new HttpResult(false, "", "MCP 工具目录请求被中断。");
        } catch (IllegalArgumentException exception) {
            return new HttpResult(false, "", "MCP 工具目录地址无效：" + exception.getMessage());
        }
    }

    private McpToolSettingsCheckResponse healthCheck(HealthSnapshot health) {
        if (!healthCheckEnabled) {
            return check("MCP_HEALTH", "MCP 健康检查", "WARN", health.message());
        }
        if (health.available()) {
            return check("MCP_HEALTH", "MCP 健康检查", "PASS", "工具目录服务健康状态：" + health.status() + "。");
        }
        return check("MCP_HEALTH", "MCP 健康检查", "BLOCKED", health.message());
    }

    private McpToolSettingsCheckResponse catalogCheck(CatalogSnapshot catalog) {
        if (catalog.available() && catalog.toolCount() > 0) {
            return check("MCP_CATALOG", "工具目录", "PASS", "读取到 " + catalog.toolCount() + " 个工具定义。");
        }
        return check("MCP_CATALOG", "工具目录", "BLOCKED", catalog.message());
    }

    private McpToolSettingsCheckResponse protocolCheck(CatalogSnapshot catalog) {
        if (!catalog.available()) {
            return check("MCP_PROTOCOL", "协议版本", "BLOCKED", "工具目录不可用，无法确认协议版本。");
        }
        if (EXPECTED_PROTOCOL_VERSION.equals(catalog.protocolVersion())) {
            return check("MCP_PROTOCOL", "协议版本", "PASS", "协议版本为 " + catalog.protocolVersion() + "。");
        }
        return check("MCP_PROTOCOL", "协议版本", "BLOCKED", "期望 " + EXPECTED_PROTOCOL_VERSION + "，实际 " + catalog.protocolVersion() + "。");
    }

    private McpToolSettingsCheckResponse requiredToolsCheck(CatalogSnapshot catalog) {
        if (!catalog.available()) {
            return check("MCP_REQUIRED_TOOLS", "MVP 工具", "BLOCKED", "工具目录不可用，无法确认 MVP 工具。");
        }
        if (catalog.missingRequiredTools().isEmpty()) {
            return check("MCP_REQUIRED_TOOLS", "MVP 工具", "PASS", "关键工具已就绪：" + String.join(", ", REQUIRED_TOOLS) + "。");
        }
        return check("MCP_REQUIRED_TOOLS", "MVP 工具", "BLOCKED", "缺少关键工具：" + String.join(", ", catalog.missingRequiredTools()) + "。");
    }

    private McpToolSettingsCheckResponse approvalGateCheck(CatalogSnapshot catalog) {
        if (!catalog.available()) {
            return check("MCP_APPROVAL_GATE", "写型工具审批门", "BLOCKED", "工具目录不可用，无法确认写型工具审批门。");
        }
        if (catalog.writeToolCount() > 0 && catalog.approvalRequiredToolCount() >= catalog.writeToolCount()) {
            return check("MCP_APPROVAL_GATE", "写型工具审批门", "PASS", "全部写型工具都要求人工审批。");
        }
        return check("MCP_APPROVAL_GATE", "写型工具审批门", "BLOCKED", "存在写型工具未声明 approvalRequired=true。");
    }

    private McpToolSettingsCheckResponse check(String code, String label, String status, String message) {
        return new McpToolSettingsCheckResponse(code, label, status, message);
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank() ? "http://127.0.0.1:8095" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) {
            return "空响应";
        }
        String compacted = value.replaceAll("\\s+", " ").trim();
        return compacted.length() > 240 ? compacted.substring(0, 240) + "..." : compacted;
    }

    private String text(JsonNode node, String fieldName, String fallback) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.asText() : fallback;
    }

    private int intValue(JsonNode node, String fieldName, int fallback) {
        JsonNode value = node.path(fieldName);
        return value.isInt() ? value.asInt() : fallback;
    }

    private record HealthSnapshot(boolean available, String status, String message) {
    }

    private record HttpResult(boolean successful, String body, String message) {
    }

    private record CatalogSnapshot(
            boolean available,
            String message,
            String serviceName,
            String protocolVersion,
            int toolCount,
            int mvpToolCount,
            int readToolCount,
            int writeToolCount,
            int auditRequiredToolCount,
            int approvalRequiredToolCount,
            List<String> missingRequiredTools,
            List<String> categories,
            List<McpToolSummaryResponse> tools
    ) {

        static CatalogSnapshot failed(String message) {
            return new CatalogSnapshot(false, message, null, null, 0, 0, 0, 0, 0, 0, REQUIRED_TOOLS, List.of(), List.of());
        }
    }
}
