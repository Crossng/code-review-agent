# 后端模块设计

## 1. 包结构

```text
com.repopilot
├── RepopilotApplication.java
├── auth
├── user
├── project
├── repository
├── indexer
├── agent
├── dashboard
├── tool
├── sandbox
├── approval
├── pullrequest
├── trace
├── notification
├── settings
├── config
└── common
```

## 2. 模块职责

| 模块 | 职责 | 关键对象 |
| --- | --- | --- |
| `auth` | 登录、JWT、权限拦截 | `LoginRequest`, `JwtTokenProvider`, `SecurityConfig` |
| `user` | 用户资料与凭据 | `User`, `UserService`, `UserRepository` |
| `project` | GitHub 仓库接入与项目管理 | `Project`, `ProjectService`, `ProjectController` |
| `repository` | clone、pull、branch、commit、工作区管理 | `RepositorySnapshot`, `GitService` |
| `indexer` | AST 解析、代码切片、向量化 | `CodeFile`, `CodeSymbol`, `CodeChunk`, `IndexJob` |
| `agent` | Agent 任务、运行记录、状态机 | `AgentTask`, `AgentRun`, `AgentStep` |
| `dashboard` | 当前用户工作区项目、任务、PR 聚合指标、最近 run 表现指标和跨项目活动流 | `DashboardController`, `DashboardSummaryService`, `DashboardSummaryResponse`, `DashboardRunMetricsResponse`, `DashboardActivityItemResponse` |
| `tool` | MCP 工具封装和工具注册 | `ToolCallLog`, `ToolExecutionService` |
| `sandbox` | Docker 容器执行、日志采集、资源限制 | `SandboxRun`, `SandboxService` |
| `approval` | diff 审批、拒绝、重新生成 | `ApprovalRecord`, `ApprovalService` |
| `pullrequest` | PR 准备记录、本地分支/commit 物化、后续 GitHub PR 创建和状态同步 | `PullRequestRecord`, `PullRequestService`, `PullRequestGitService` |
| `trace` | 模型调用、工具调用、步骤日志 | `ModelCallLog`, `TraceQueryService` |
| `notification` | SSE 或 WebSocket 事件推送 | `AgentEvent`, `TaskStreamController` |
| `settings` | 运行时配置只读可见性和脱敏状态 | `CoderSettingsController`, `GitHubSettingsController`, `SandboxSettingsController` |
| `config` | 模型、GitHub、Docker、向量库配置 | `ModelConfig`, `GitHubConfig`, `DockerConfig` |
| `common` | 统一响应、异常、分页、审计字段 | `ApiResponse`, `ErrorCode`, `BaseEntity` |

## 3. 分层约定

每个业务模块优先采用以下结构：

```text
module
├── controller
├── service
├── repository
├── domain
├── dto
└── mapper
```

约定：

- Controller 只处理 HTTP 入参、鉴权上下文和响应封装。
- Service 负责业务流程和事务边界。
- Repository 负责数据库访问。
- MCP 工具调用放在 `tool` 或专门 client 中，避免散落在 Controller。
- Agent 状态流转只能通过 `AgentTaskStateMachine` 或等价服务修改。

## 4. 核心领域对象

### Project

| 字段 | 说明 |
| --- | --- |
| `id` | 项目 ID |
| `owner_user_id` | 所属用户 |
| `repo_url` | GitHub 仓库地址 |
| `repo_full_name` | `owner/name` |
| `default_branch` | 默认分支 |
| `local_path` | 受控工作区路径 |
| `status` | `CREATED`、`CLONING`、`READY`、`FAILED` |

### AgentTask

| 字段 | 说明 |
| --- | --- |
| `id` | 任务 ID |
| `project_id` | 关联项目 |
| `task_type` | `FEATURE`、`BUGFIX`、`REVIEW`、`DOC` |
| `title` | 任务标题 |
| `description` | 自然语言需求 |
| `status` | 当前状态 |
| `current_run_id` | 当前运行 ID |

### PatchRecord

| 字段 | 说明 |
| --- | --- |
| `id` | patch ID |
| `agent_task_id` | 关联任务 |
| `diff_content` | unified diff |
| `summary` | 修改摘要 |
| `status` | `GENERATED`、`APPLIED`、`APPROVED`、`REJECTED` |

## 5. 状态枚举

### AgentTaskStatus

```text
CREATED
REPO_INDEXING
PLANNING
RETRIEVING_CONTEXT
GENERATING_PATCH
APPLYING_PATCH_IN_SANDBOX
RUNNING_TESTS
REPAIRING
REVIEWING_PATCH
WAITING_HUMAN_APPROVAL
CREATING_PULL_REQUEST
DONE
FAILED_REPO_CLONE
FAILED_INDEXING
FAILED_CONTEXT_RETRIEVAL
FAILED_PATCH_GENERATION
FAILED_TEST
FAILED_PR_CREATION
CANCELLED
```

## 6. 后端任务执行策略

- 当前 MVP 主链路由 `POST /api/agent/tasks/{id}/run` 创建 `agent_run`、切换任务到 `GENERATING_PATCH`，并提交到后台 `agentTaskExecutor` 执行；HTTP 请求会立即返回 RUNNING 状态的 run。
- 后台执行使用独立事务继续完成上下文加载、检索、patch 生成、沙箱验证、RepairAgent 和 ReviewAgent；异常会回写 run 失败状态与任务失败状态。
- `POST /api/agent/tasks/{id}/cancel` 会立即将 task 与当前 RUNNING run 标为 `CANCELLED`；后台 run 在上下文、检索、patch 生成、沙箱应用、测试、修复和审查等关键阶段检查最新 task 状态，发现取消后停止继续推进。
- `POST /api/tasks/{id}/patches/regenerate` 仍保持同步执行，用于审批前快速生成并返回新的 patch 版本。
- `ProjectWriteGuardService` 会在启动任务、重新生成 patch、审批通过和 PR 准备前用项目行级锁检查同项目写入槽；若已有其他任务处于写入型状态，接口返回 `409 PROJECT_WRITE_TASK_RUNNING`，避免多个后台 run 同时修改同一工作区。
- 已提供 `GET /api/agent/tasks/{id}/stream` SSE：连接后先发送当前 task/run/step 快照；任务运行中会继续推送 `TASK_UPDATED`、`STEP_RECORDED`，并在审批点、失败、取消或完成时发送 `STREAM_COMPLETE` 后关闭。
- 可通过 `REPOPILOT_AGENT_WORKER_ENABLED=true` 打开 Agent Worker 启动桥；后台执行会调用 `${REPOPILOT_AGENT_WORKER_URL}/runs/{runId}/start` 并写入 `agent_worker_start` step，调用失败只记录失败证据，现阶段仍由 Spring Boot executor 继续兜底。
- Agent Worker 可通过 `POST /api/internal/agent-worker/runs/{runId}/steps` 回写 step 证据，通过 `/tool-calls` 和 `/model-calls` 回写工具/模型调用审计，通过 `/patches` 回写生成的 patch draft，通过 `/status` 回写 task/run 状态；内部 callback 由 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 保护，step/status 成功落库后分别发布 `STEP_RECORDED`、`TASK_UPDATED`，并在 `complete_stream=true` 时发布 `STREAM_COMPLETE`。patch draft 只负责落入 `patch_record`，后续仍必须经过 diff 安全预检、沙箱测试、风险审查和人工审批。
- Agent Worker 可通过 `GET /api/internal/agent-worker/runs/{runId}/context`、`/project/files`、`/project/file`、`/project/search` 和 `/project/symbols` 读取 run 作用域内的任务上下文、文件树、文件内容、代码检索结果和符号；后端按 run 反查 task/project，`read_file` 拒绝越权路径和 `.git` 内部路径，作为正式 MCP Tool Server 拆出前的内部工具桥。
- 配置 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 后，Python Worker 的 `/runs/{runId}/start` 会通过轻量图执行器后台执行 `load_task_context`、`ensure_index`、确定性 `plan_task`、`retrieve_context` 和 `generate_patch`，通过内部工具桥读取上下文、确认索引信号、检索代码、读取关键文件预览，自动通过 `/tool-calls` 回写每次工具读取的 SUCCESS/FAILED 审计摘要，并通过 `/model-calls` 与 `/patches` 回写 `WORKER_SAFE_PLANNING_DRAFT` 补丁草稿，再通过 `/steps` 回写 SUCCESS 证据；未配置 token 时 `/start` 只返回启动契约。
- 同一个项目同一时间 MVP 只允许一个写入型 Agent 任务运行，避免工作区冲突。

## 7. 异常处理

统一错误响应：

```json
{
  "success": false,
  "code": "PROJECT_CLONE_FAILED",
  "message": "Clone repository failed",
  "traceId": "..."
}
```

错误码分组：

- `AUTH_*`
- `PROJECT_*`
- `INDEX_*`
- `AGENT_*`
- `TOOL_*`
- `SANDBOX_*`
- `GITHUB_*`
- `PULL_REQUEST_*`
