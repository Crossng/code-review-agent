# MCP 工具设计

## 1. 工具层目标

MCP Tool Server 把 RepoPilot 的工程能力封装成 Agent 可调用的工具。所有工具必须可审计、可限权、可复现。MVP 中工具先由 Spring Boot 内部 HTTP 工具桥提供，Agent Worker 使用 `X-RepoPilot-Worker-Token` 访问 run-scoped 工具接口；等工具清单稳定后，再拆成 Spring AI MCP Server。

## 2. 工具分类

| 分类 | 工具 |
| --- | --- |
| 仓库读取 | `list_project_files`, `read_file`, `search_code` |
| Java 结构分析 | `get_class_structure`, `find_controller_api`, `find_call_chain` |
| Git 操作 | `create_branch`, `get_git_diff`, `commit_changes` |
| Patch 操作 | `apply_patch`, `validate_unified_diff` |
| 构建测试 | `run_maven_compile`, `run_maven_test` |
| GitHub 集成 | `create_pull_request` |
| 审计辅助 | `record_tool_call`, `summarize_test_log` |

## 3. MVP 工具清单

### `list_project_files`

用途：列出项目受控工作区内的文件树。

输入：

```json
{
  "projectId": 1,
  "root": "src/main/java",
  "maxDepth": 5
}
```

输出：

```json
{
  "files": [
    {
      "path": "src/main/java/com/example/UserController.java",
      "type": "FILE",
      "size": 2048
    }
  ]
}
```

当前 MVP 内部工具接口：

```text
GET /api/internal/agent-worker/runs/{runId}/project/files?maxDepth=6
```

### `read_file`

用途：读取仓库内指定文件。

安全约束：

- path 必须是相对路径。
- 禁止 `..` 越权。
- 单次读取大小默认限制为 200 KB。

输入：

```json
{
  "projectId": 1,
  "path": "src/main/java/com/example/UserController.java"
}
```

当前 MVP 内部工具接口：

```text
GET /api/internal/agent-worker/runs/{runId}/project/file?path=src/main/java/com/example/UserController.java
```

### `search_code`

用途：根据关键词或向量检索代码 chunk。

输入：

```json
{
  "projectId": 1,
  "query": "User 分页查询接口",
  "topK": 8,
  "filters": {
    "symbolType": ["CONTROLLER", "SERVICE", "MAPPER"]
  }
}
```

当前 MVP 内部工具接口：

```text
GET /api/internal/agent-worker/runs/{runId}/project/search?query=User%20Controller&limit=8
```

输出：

```json
{
  "results": [
    {
      "chunkId": 101,
      "path": "src/main/java/com/example/UserService.java",
      "score": 0.81,
      "contentPreview": "public Page<User> ..."
    }
  ]
}
```

### `get_class_structure`

用途：获取 Java 类结构，包括字段、方法、注解和继承关系。

输入：

```json
{
  "projectId": 1,
  "path": "src/main/java/com/example/UserController.java"
}
```

当前 MVP 可先通过符号接口提供类级结构入口：

```text
GET /api/internal/agent-worker/runs/{runId}/project/symbols?type=CONTROLLER
```

### `find_controller_api`

用途：扫描 Spring Controller API。

输出字段：

- controller 类名。
- method 名称。
- HTTP method。
- route path。
- request/response 类型。
- 鉴权注解。
- 风险分、风险等级、风险提示和字段/参数级风险细节。

### `validate_unified_diff`

用途：检查 diff 格式是否合法，是否只修改允许路径。

### `apply_patch`

用途：在沙箱工作区应用 unified diff。

输入：

```json
{
  "projectId": 1,
  "runId": 1,
  "diff": "diff --git ..."
}
```

输出：

```json
{
  "applied": true,
  "changedFiles": ["src/main/java/com/example/UserController.java"]
}
```

### `run_maven_compile`

用途：在沙箱执行 `mvn compile`。

### `run_maven_test`

用途：在沙箱执行 `mvn test`。

输出：

```json
{
  "exitCode": 0,
  "durationMs": 43120,
  "stdoutLogId": 501,
  "stderrLogId": 502,
  "summary": "Tests run: 18, Failures: 0, Errors: 0"
}
```

### `create_branch`

用途：从 base branch 创建任务分支。

### `commit_changes`

用途：提交沙箱中已审批的修改。

### `create_pull_request`

用途：调用 GitHub API 创建 PR。

输入：

```json
{
  "projectId": 1,
  "title": "feat: add user pagination API",
  "body": "由 RepoPilot 生成...",
  "head": "repopilot/task-1",
  "base": "main"
}
```

## 4. 工具调用审计

每次调用写入 `tool_call_log`：

| 字段 | 说明 |
| --- | --- |
| `tool_name` | 工具名 |
| `input_json` | 输入参数，敏感字段脱敏 |
| `output_json` | 输出摘要，避免保存超大日志全文 |
| `status` | `SUCCESS` 或 `FAILED` |
| `duration_ms` | 耗时 |
| `error_message` | 失败原因 |

## 5. 安全规则

- 工具默认只能访问项目工作区。
- 文件路径统一使用项目相对路径。
- GitHub token、模型 key、数据库密码必须脱敏。
- `apply_patch` 必须先调用 `validate_unified_diff`。
- 创建 PR 前必须确认 patch 状态为 `APPROVED`。
- Maven 执行必须有超时和日志大小限制。
