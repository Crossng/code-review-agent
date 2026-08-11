package com.repopilot.toolserver.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class McpToolRegistryService {

    public static final String PROTOCOL_VERSION = "REPOPILOT_MCP_CONTRACT_V1";
    private static final String BACKEND_RUN_BRIDGE = "backend:/api/internal/agent-worker/runs/{runId}";

    private final Map<String, McpToolDefinition> toolsByName;

    public McpToolRegistryService() {
        this.toolsByName = toolDefinitions().stream()
                .collect(Collectors.toMap(
                        McpToolDefinition::name,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    public List<McpToolDefinition> listTools() {
        return List.copyOf(toolsByName.values());
    }

    public McpToolDefinition getTool(String toolName) {
        McpToolDefinition tool = toolsByName.get(normalizeToolName(toolName));
        if (tool == null) {
            throw new ToolNotFoundException(toolName);
        }
        return tool;
    }

    public ToolValidationResponse validate(String toolName, Map<String, Object> arguments) {
        McpToolDefinition tool = getTool(toolName);
        Map<String, Object> normalizedArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        List<String> messages = new ArrayList<>();

        for (McpToolArgumentDefinition argument : tool.arguments()) {
            if (argument.required() && missing(normalizedArguments.get(argument.name()))) {
                messages.add("缺少必填参数：" + argument.name());
            }
            validateDeclaredArgumentType(argument, normalizedArguments.get(argument.name()), messages);
        }
        validateKnownArguments(tool, normalizedArguments, messages);
        validateKnownPathArgument(normalizedArguments, "path", messages);
        validateKnownPathArgument(normalizedArguments, "root", messages);
        validateIntegerRange(normalizedArguments, "limit", 1, 20, messages);
        validateIntegerRange(normalizedArguments, "maxDepth", 1, 10, messages);
        validateApprovalGate(tool, normalizedArguments, messages);

        String status = messages.isEmpty()
                ? "READY"
                : approvalMessagesOnly(messages) ? "NEEDS_HUMAN_APPROVAL" : "BLOCKED";
        return new ToolValidationResponse(
                tool.name(),
                messages.isEmpty(),
                status,
                List.copyOf(messages),
                Map.copyOf(normalizedArguments),
                tool.approvalRequired(),
                tool.auditRequired()
        );
    }

    private List<McpToolDefinition> toolDefinitions() {
        return List.of(
                readTool(
                        "list_project_files",
                        "列出项目文件树",
                        "仓库读取",
                        "列出任务作用域内项目工作区的文件树。",
                        true,
                        BACKEND_RUN_BRIDGE + "/project/files",
                        List.of(
                                arg("runId", "number", true, "Agent run ID，用于限定项目作用域。", null),
                                arg("root", "string", false, "可选相对根目录。", "."),
                                arg("maxDepth", "number", false, "最大遍历深度，建议 1-10。", 6)
                        ),
                        pathRules()
                ),
                readTool(
                        "read_file",
                        "读取项目文件",
                        "仓库读取",
                        "读取任务作用域项目工作区内的单个 UTF-8 文件。",
                        true,
                        BACKEND_RUN_BRIDGE + "/project/file",
                        List.of(
                                arg("runId", "number", true, "Agent run ID，用于限定项目作用域。", null),
                                arg("path", "string", true, "项目工作区内相对文件路径。", null)
                        ),
                        pathRules()
                ),
                readTool(
                        "search_code",
                        "检索代码上下文",
                        "仓库读取",
                        "按关键词检索任务作用域项目的代码 chunk。",
                        true,
                        BACKEND_RUN_BRIDGE + "/project/search",
                        List.of(
                                arg("runId", "number", true, "Agent run ID，用于限定项目作用域。", null),
                                arg("query", "string", true, "检索关键词。", null),
                                arg("limit", "number", false, "返回结果数量，范围 1-20。", 8)
                        ),
                        List.of("查询词会进入工具审计，不能包含 token 或密钥。")
                ),
                readTool(
                        "get_class_structure",
                        "读取 Java 类结构",
                        "Java 结构分析",
                        "读取类、方法、字段和注解结构，MVP 由符号接口提供类级入口。",
                        true,
                        BACKEND_RUN_BRIDGE + "/project/symbols",
                        List.of(
                                arg("runId", "number", true, "Agent run ID，用于限定项目作用域。", null),
                                arg("path", "string", false, "可选 Java 文件相对路径。", null),
                                arg("type", "string", false, "可选符号类型过滤。", "CLASS")
                        ),
                        pathRules()
                ),
                readTool(
                        "find_controller_api",
                        "查找 Controller API",
                        "Java 结构分析",
                        "枚举 Spring Controller 路由、HTTP 方法、参数和风险提示。",
                        true,
                        "backend:/api/projects/{projectId}/controller-apis",
                        List.of(
                                arg("projectId", "number", true, "项目 ID。", null),
                                arg("riskLevel", "string", false, "可选风险等级过滤。", null)
                        ),
                        List.of("只能读取当前用户授权项目的 Controller API 摘要。")
                ),
                readTool(
                        "find_call_chain",
                        "查找调用链",
                        "Java 结构分析",
                        "分析 Controller 到 Service、Mapper 的调用关系。",
                        false,
                        "planned:call-chain-analyzer",
                        List.of(
                                arg("projectId", "number", true, "项目 ID。", null),
                                arg("entrypoint", "string", true, "Controller 方法或路由入口。", null)
                        ),
                        List.of("只返回静态分析摘要，不执行项目代码。")
                ),
                readTool(
                        "validate_unified_diff",
                        "校验 unified diff",
                        "Patch 操作",
                        "检查 diff 格式、路径边界和保留目录修改风险。",
                        true,
                        "backend:PatchDiffSafetyService",
                        List.of(arg("diff", "string", true, "待校验的 raw unified diff。", null)),
                        List.of("拒绝绝对路径、路径穿越、.git、target、node_modules 和二进制 patch。")
                ),
                writeTool(
                        "apply_patch",
                        "应用补丁到沙箱",
                        "Patch 操作",
                        "在隔离工作区应用已通过安全预检的 unified diff。",
                        true,
                        "backend:SandboxTestService",
                        List.of(
                                arg("runId", "number", true, "Agent run ID。", null),
                                arg("patchId", "number", true, "补丁 ID。", null),
                                arg("approvedByHuman", "boolean", false, "是否已经经过人工审批。", false)
                        ),
                        List.of("写入型工具必须先有 patch 安全预检记录，并进入审计。")
                ),
                writeTool(
                        "run_maven_compile",
                        "运行 Maven compile",
                        "构建测试",
                        "在 Docker 沙箱中执行 Maven 编译。",
                        true,
                        "backend:SandboxTestService",
                        List.of(
                                arg("runId", "number", true, "Agent run ID。", null),
                                arg("command", "string", false, "固定为 mvn -q compile。", "mvn -q compile"),
                                arg("approvedByHuman", "boolean", false, "是否允许执行写型沙箱动作。", false)
                        ),
                        sandboxRules()
                ),
                writeTool(
                        "run_maven_test",
                        "运行 Maven test",
                        "构建测试",
                        "在 Docker 沙箱中执行 Maven 测试。",
                        true,
                        "backend:SandboxTestService",
                        List.of(
                                arg("runId", "number", true, "Agent run ID。", null),
                                arg("command", "string", false, "固定为 mvn -q test。", "mvn -q test"),
                                arg("approvedByHuman", "boolean", false, "是否允许执行写型沙箱动作。", false)
                        ),
                        sandboxRules()
                ),
                writeTool(
                        "create_branch",
                        "创建任务分支",
                        "Git 操作",
                        "从基线分支创建 RepoPilot 任务分支。",
                        true,
                        "backend:PullRequestGitService",
                        List.of(
                                arg("taskId", "number", true, "Agent task ID。", null),
                                arg("baseBranch", "string", true, "基线分支。", null),
                                arg("targetBranch", "string", true, "目标分支。", null),
                                arg("approvedByHuman", "boolean", false, "是否已经过人工审批。", false)
                        ),
                        gitWriteRules()
                ),
                readTool(
                        "get_git_diff",
                        "读取 Git diff",
                        "Git 操作",
                        "读取当前任务分支的 Git diff 摘要。",
                        true,
                        "backend:PullRequestGitService",
                        List.of(
                                arg("taskId", "number", true, "Agent task ID。", null),
                                arg("targetBranch", "string", true, "目标分支。", null)
                        ),
                        List.of("只读取目标任务工作区内 diff，不访问用户全局 Git 配置。")
                ),
                writeTool(
                        "commit_changes",
                        "提交任务修改",
                        "Git 操作",
                        "提交已审批 patch 对应的工作区修改。",
                        true,
                        "backend:PullRequestGitService",
                        List.of(
                                arg("taskId", "number", true, "Agent task ID。", null),
                                arg("message", "string", true, "提交信息。", null),
                                arg("approvedByHuman", "boolean", false, "是否已经过人工审批。", false)
                        ),
                        gitWriteRules()
                ),
                writeTool(
                        "create_pull_request",
                        "创建 GitHub PR",
                        "GitHub 集成",
                        "推送目标分支并通过 GitHub API 创建 PR。",
                        true,
                        "backend:GitHubPullRequestService",
                        List.of(
                                arg("taskId", "number", true, "Agent task ID。", null),
                                arg("title", "string", true, "PR 标题。", null),
                                arg("body", "string", true, "PR 描述。", null),
                                arg("approvedByHuman", "boolean", false, "是否已经过人工审批。", false)
                        ),
                        List.of("必须有已审批 patch、通过的沙箱测试和 PR preflight。", "GitHub token 不得进入工具审计明文。")
                ),
                readTool(
                        "query_db_schema",
                        "查询数据库结构",
                        "审计辅助",
                        "读取受控数据库 schema 摘要。",
                        false,
                        "planned:schema-reader",
                        List.of(arg("projectId", "number", true, "项目 ID。", null)),
                        List.of("只返回 schema 摘要，不读取业务数据行。")
                ),
                readTool(
                        "generate_api_doc",
                        "生成接口文档",
                        "审计辅助",
                        "基于 Controller API 摘要生成 Markdown 文档。",
                        false,
                        "backend:SpringControllerApiService",
                        List.of(
                                arg("projectId", "number", true, "项目 ID。", null),
                                arg("limit", "number", false, "最大路由数。", 12)
                        ),
                        List.of("输出需要保留风险等级和字段级风险提示。")
                ),
                readTool(
                        "run_security_check",
                        "运行安全检查",
                        "审计辅助",
                        "检查 SQL 注入、鉴权缺失、参数校验和敏感字段暴露风险。",
                        false,
                        "backend:PatchRiskReviewService",
                        List.of(
                                arg("projectId", "number", true, "项目 ID。", null),
                                arg("path", "string", false, "可选相对文件路径。", null)
                        ),
                        pathRules()
                )
        );
    }

    private McpToolDefinition readTool(
            String name,
            String title,
            String category,
            String description,
            boolean mvp,
            String backendBridge,
            List<McpToolArgumentDefinition> arguments,
            List<String> safetyRules
    ) {
        return tool(name, title, category, description, mvp, McpToolAccessMode.READ, false, backendBridge, arguments, safetyRules);
    }

    private McpToolDefinition writeTool(
            String name,
            String title,
            String category,
            String description,
            boolean mvp,
            String backendBridge,
            List<McpToolArgumentDefinition> arguments,
            List<String> safetyRules
    ) {
        return tool(name, title, category, description, mvp, McpToolAccessMode.WRITE, true, backendBridge, arguments, safetyRules);
    }

    private McpToolDefinition tool(
            String name,
            String title,
            String category,
            String description,
            boolean mvp,
            McpToolAccessMode accessMode,
            boolean approvalRequired,
            String backendBridge,
            List<McpToolArgumentDefinition> arguments,
            List<String> safetyRules
    ) {
        return new McpToolDefinition(
                name,
                title,
                category,
                description,
                mvp,
                accessMode,
                true,
                approvalRequired,
                backendBridge,
                arguments,
                safetyRules
        );
    }

    private McpToolArgumentDefinition arg(String name, String type, boolean required, String description, Object defaultValue) {
        return new McpToolArgumentDefinition(name, type, required, description, defaultValue, List.of());
    }

    private List<String> pathRules() {
        return List.of(
                "路径必须是项目工作区内相对路径。",
                "禁止绝对路径、.. 路径穿越和 .git 内部路径。",
                "单文件读取默认限制为 200 KB。"
        );
    }

    private List<String> sandboxRules() {
        return List.of(
                "命令只能在 Docker 沙箱或隔离工作区执行。",
                "Maven cache 只读复用，测试输出进入审计摘要。",
                "写型工具必须经过安全门并记录 tool_call_log。"
        );
    }

    private List<String> gitWriteRules() {
        return List.of(
                "写入前必须确认任务处于 PR 准备状态。",
                "目标路径必须在 RepoPilot 管理的 workspace 内。",
                "提交和分支信息必须进入 PR 记录。"
        );
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    private boolean missing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private void validateKnownArguments(
            McpToolDefinition tool,
            Map<String, Object> arguments,
            List<String> messages
    ) {
        Set<String> knownArgumentNames = tool.arguments().stream()
                .map(McpToolArgumentDefinition::name)
                .collect(Collectors.toSet());
        for (String suppliedName : arguments.keySet()) {
            if (!knownArgumentNames.contains(suppliedName)) {
                messages.add("未知参数：" + suppliedName);
            }
        }
    }

    private void validateDeclaredArgumentType(
            McpToolArgumentDefinition argument,
            Object value,
            List<String> messages
    ) {
        if (missing(value)) {
            return;
        }
        boolean matches = switch (argument.type()) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number number && finiteNumber(number);
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
        if (!matches) {
            messages.add("参数 " + argument.name() + " 必须是 " + argument.type() + "。");
        }
    }

    private void validateKnownPathArgument(Map<String, Object> arguments, String key, List<String> messages) {
        Object value = arguments.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof String pathText)) {
            return;
        }
        if (pathText.isBlank()) {
            messages.add("路径参数必须是非空字符串：" + key);
            return;
        }
        String normalized = pathText.trim().replace('\\', '/');
        arguments.put(key, normalized);
        if (!safeRelativePath(normalized)) {
            messages.add("路径参数越界或访问保留目录：" + key);
        }
    }

    private boolean safeRelativePath(String pathText) {
        if (pathText.startsWith("/")
                || pathText.matches("^[A-Za-z]:.*")) {
            return false;
        }
        Path rawPath = Path.of(pathText);
        if (rawPath.isAbsolute()) {
            return false;
        }
        for (Path part : rawPath) {
            String segment = part.toString();
            if ("..".equals(segment) || ".git".equals(segment)) {
                return false;
            }
        }
        Path normalizedPath = rawPath.normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
            return false;
        }
        return true;
    }

    private void validateIntegerRange(
            Map<String, Object> arguments,
            String key,
            int min,
            int max,
            List<String> messages
    ) {
        Object value = arguments.get(key);
        if (value == null || !(value instanceof Number number)) {
            return;
        }
        double numericValue = number.doubleValue();
        if (!finiteNumber(number) || numericValue % 1 != 0 || numericValue < min || numericValue > max) {
            messages.add(key + " 必须是 " + min + " 到 " + max + " 之间的整数。");
        }
    }

    private boolean finiteNumber(Number number) {
        double value = number.doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private void validateApprovalGate(
            McpToolDefinition tool,
            Map<String, Object> arguments,
            List<String> messages
    ) {
        if (!tool.approvalRequired()) {
            return;
        }
        if (!Boolean.TRUE.equals(arguments.get("approvedByHuman"))) {
            messages.add("写型工具需要人工审批确认 approvedByHuman=true。");
        }
    }

    private boolean approvalMessagesOnly(List<String> messages) {
        return !messages.isEmpty() && messages.stream().allMatch(message -> message.contains("人工审批确认"));
    }
}
