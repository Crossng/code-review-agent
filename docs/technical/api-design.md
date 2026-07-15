# API 设计

## 1. 通用约定

Base URL:

```text
/api
```

认证：

```text
Authorization: Bearer <jwt>
```

统一成功响应：

```json
{
  "success": true,
  "data": {},
  "traceId": "01HY..."
}
```

统一失败响应：

```json
{
  "success": false,
  "code": "AGENT_TASK_NOT_FOUND",
  "message": "Agent task not found",
  "traceId": "01HY..."
}
```

## 2. Auth API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `POST` | `/auth/register` | 注册 |
| `POST` | `/auth/login` | 登录 |
| `GET` | `/auth/me` | 当前用户 |

### POST `/auth/login`

请求：

```json
{
  "email": "dev@example.com",
  "password": "password"
}
```

响应：

```json
{
  "token": "jwt",
  "expiresIn": 7200
}
```

## 3. Dashboard API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `GET` | `/dashboard/summary` | 当前用户工作区概览计数 |
| `GET` | `/dashboard/run-metrics` | 当前用户最近 Agent run 表现指标 |
| `GET` | `/dashboard/activity` | 当前用户最近 Agent step 活动流 |

### GET `/dashboard/summary`

返回当前登录用户拥有的项目、Agent 任务和 PR 记录计数。该接口需要 JWT 鉴权，只返回聚合指标，不返回其他用户数据。

响应：

```json
{
  "totalProjects": 3,
  "readyProjects": 1,
  "failedProjects": 1,
  "totalTasks": 6,
  "createdTasks": 1,
  "runningTasks": 1,
  "waitingApprovalTasks": 1,
  "doneTasks": 1,
  "failedTasks": 1,
  "cancelledTasks": 1,
  "totalPullRequests": 3,
  "draftPullRequests": 1,
  "openPullRequests": 1,
  "failedPullRequests": 1
}
```

`runningTasks` 汇总索引、规划、检索、patch 生成、沙箱应用、测试、修复、自动审查和 PR 创建中的任务。`failedTasks` 汇总 clone、索引、检索、patch 生成、测试和 PR 创建失败状态。PR 计数按 `pull_request_record` 当前状态聚合，`draftPullRequests` 对应本地 `DRAFT_READY` 记录。

### GET `/dashboard/run-metrics`

返回当前登录用户最近 Agent run 表现指标。该接口需要 JWT 鉴权，只统计当前用户的 `agent_run`，默认统计最近 7 天，可通过 `days` 查询参数指定 1-30 天窗口。

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `days` | 否 | 最近 N 天窗口，默认 7，服务端限制为 1-30 |

响应：

```json
{
  "days": 7,
  "from": "2026-07-09T00:00:00Z",
  "to": "2026-07-15T11:40:00Z",
  "totalRuns": 4,
  "successRuns": 2,
  "failedRuns": 1,
  "cancelledRuns": 0,
  "runningRuns": 1,
  "completedRuns": 3,
  "averageDurationSeconds": 120,
  "successRatePercent": 67,
  "trend": [
    {
      "date": "2026-07-15",
      "totalRuns": 3,
      "successRuns": 1,
      "failedRuns": 1,
      "cancelledRuns": 0,
      "runningRuns": 1,
      "averageDurationSeconds": 120
    }
  ]
}
```

`trend` 使用 UTC 日期按天升序返回，长度等于 `days`，没有 run 的日期返回 0。`averageDurationSeconds` 只统计已有 `finishedAt` 的 run，`successRatePercent` 使用成功 run 数除以已完成 run 数四舍五入得到；没有已完成 run 时为 0。

### GET `/dashboard/activity`

返回当前登录用户最近 Agent step 活动，用于工作台跨项目活动流。该接口需要 JWT 鉴权，只统计当前用户任务下已持久化的 `agent_step`，按 `finishedAt`/`startedAt` 倒序返回，不包含 step 的 `inputJson` 或 `outputJson` 大 payload。

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `limit` | 否 | 返回条数，默认 10，服务端限制为 1-50 |

响应：

```json
[
  {
    "stepId": 5001,
    "runId": 2001,
    "taskId": 1001,
    "projectId": 1,
    "projectName": "example/demo-spring",
    "taskTitle": "Add User pagination API",
    "taskStatus": "WAITING_HUMAN_APPROVAL",
    "activityType": "AGENT_STEP",
    "label": "waiting_human_approval",
    "status": "SUCCESS",
    "message": "waiting_human_approval SUCCESS",
    "occurredAt": "2026-07-15T09:03:05Z"
  }
]
```

`occurredAt` 优先使用 step `finishedAt`，运行中或待处理 step 则使用 `startedAt`。该接口面向 dashboard 列表摘要；需要查看完整 step 输入输出时仍使用任务详情和 trace API。

## 4. Project API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `POST` | `/projects` | 添加 GitHub 仓库 |
| `GET` | `/projects` | 项目列表 |
| `GET` | `/projects/{id}` | 项目详情 |
| `POST` | `/projects/{id}/clone` | 重新 clone |
| `POST` | `/projects/{id}/index` | 触发代码索引 |
| `GET` | `/projects/{id}/files` | 文件树 |
| `GET` | `/projects/{id}/symbols` | 代码符号 |
| `GET` | `/projects/{id}/controller-apis` | Controller API 列表 |
| `GET` | `/projects/{id}/controller-apis/docs` | 生成 Controller API Markdown 文档 |
| `POST` | `/projects/{id}/controller-apis/docs/snapshots` | 保存 Controller API 文档快照 |
| `GET` | `/projects/{id}/controller-apis/docs/snapshots` | Controller API 文档快照列表 |
| `GET` | `/projects/{id}/controller-apis/docs/snapshots/{snapshotId}` | Controller API 文档快照详情 |
| `DELETE` | `/projects/{id}/controller-apis/docs/snapshots` | 清空 Controller API 文档快照 |
| `DELETE` | `/projects/{id}/controller-apis/docs/snapshots/{snapshotId}` | 删除 Controller API 文档快照 |
| `GET` | `/projects/{id}/search` | 代码 chunk 检索 |

### POST `/projects`

请求：

```json
{
  "repoUrl": "https://github.com/example/demo-spring.git",
  "accessToken": "<github-token>",
  "defaultBranch": "main"
}
```

响应：

```json
{
  "id": 1,
  "repoFullName": "example/demo-spring",
  "status": "CREATED"
}
```

### GET `/projects`

返回当前登录用户拥有的项目列表，按创建时间倒序排列。列表支持按项目状态和仓库关键词过滤，只返回当前用户项目。

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 否 | 按项目状态过滤，可选 `CREATED`、`CLONING`、`READY`、`FAILED`；为空表示不过滤 |
| `query` | 否 | 对 `repoFullName` 和 `repoUrl` 做大小写不敏感关键词搜索 |

示例：

```text
GET /api/projects?status=READY&query=demo-spring
```

响应：

```json
[
  {
    "id": 1,
    "repoUrl": "https://github.com/example/demo-spring.git",
    "repoFullName": "example/demo-spring",
    "defaultBranch": "main",
    "localPath": "/workspace/repos/1/source",
    "status": "READY",
    "lastIndexedAt": "2026-07-15T10:30:00Z",
    "createdAt": "2026-07-15T10:00:00Z"
  }
]
```

### GET `/projects/{id}/controller-apis`

从已 clone 的 Java/Spring 项目中解析 Controller 路由，不依赖数据库索引结果，适合项目详情页快速展示当前仓库暴露的 HTTP API。

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `riskLevel` | 否 | 按风险等级过滤 `items`，可选 `HIGH`、`MEDIUM`、`LOW`、`NONE`；为空或 `ALL` 表示不过滤 |
| `riskCode` | 否 | 按风险码过滤 `items`，例如 `NO_SECURITY_ANNOTATION`；为空或 `ALL` 表示不过滤 |

响应：

```json
{
  "items": [
    {
      "filePath": "src/main/java/com/example/demo/user/UserController.java",
      "controllerName": "UserController",
      "qualifiedControllerName": "com.example.demo.user.UserController",
      "methodName": "listUsers",
      "httpMethod": "GET",
      "path": "/api/users",
      "requestType": null,
      "parameters": [
        {
          "name": "id",
          "source": "PATH",
          "type": "Long",
          "required": true,
          "defaultValue": null
        },
        {
          "name": "size",
          "source": "QUERY",
          "type": "int",
          "required": false,
          "defaultValue": "20"
        }
      ],
      "serviceCalls": [
        {
          "receiverName": "userService",
          "serviceType": "UserService",
          "methodName": "listUsers",
          "line": 20,
          "downstreamCalls": [
            {
              "receiverName": "userMapper",
              "componentType": "UserMapper",
              "methodName": "findAll",
              "line": 14
            }
          ]
        }
      ],
      "responseType": "List<UserEntity>",
      "securityAnnotations": [],
      "riskScore": 35,
      "riskLevel": "MEDIUM",
      "riskHints": [
        {
          "severity": "MEDIUM",
          "code": "NO_SECURITY_ANNOTATION",
          "message": "Endpoint has no recognized method or class security annotation.",
          "details": []
        }
      ],
      "startLine": 18,
      "endLine": 20
    }
  ],
  "filteredCount": 1,
  "riskSummary": {
    "total": 1,
    "byLevel": {
      "HIGH": 0,
      "MEDIUM": 1,
      "LOW": 0,
      "NONE": 0
    }
  },
  "riskCodes": [
    "NO_SECURITY_ANNOTATION"
  ],
  "filters": {
    "riskLevel": "MEDIUM",
    "riskCode": "NO_SECURITY_ANNOTATION"
  }
}
```

`riskHints` uses `severity` (`HIGH`/`MEDIUM`/`LOW`), `code`, `message`,
and `details`. `details` contains structured field-level, parameter-level, or
target-level values when a hint can name exact inputs.
Current Controller API risk codes include missing security annotations, unguarded mutations,
mutations without request bodies, optional request bodies, unclassified parameters,
request bodies without `@Valid`/`@Validated`, request body DTOs without recognized
validation constraints, request body DTO fields without recognized validation constraints,
and numeric pagination/limit query parameters with missing lower or upper bound annotations.
`riskScore` is a capped 0-100 aggregate of risk hints, with `riskLevel` mapped to
`NONE`, `LOW`, `MEDIUM`, or `HIGH`.
`filteredCount` is the total number of Controller APIs matching the active filters.
It currently equals `items.length`; if pagination is added later, it remains the
filtered total while `items` can represent only the current page.
`riskSummary` is computed by the API from the full Controller API result set so
the UI can show stable HIGH/MEDIUM/LOW/NONE counters even if item rendering or
future pagination changes.
`riskCodes` is a sorted, de-duplicated list of all risk hint codes in the full
Controller API result set, so the UI can keep complete risk-code filter options
even when `items` is filtered.
When `riskLevel` or `riskCode` query parameters are present, `items` is filtered
by those parameters while `riskSummary` keeps the full-result-set counts.
`filters` echoes the normalized server-side filters used to produce `items`; the
fields are `null` when no filter is active.

### GET `/projects/{id}/controller-apis/docs`

基于当前仓库中的 Spring Controller API 生成 Markdown 文档。该接口需要 JWT 鉴权，只允许读取当前用户拥有的项目。

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `riskLevel` | 否 | 与 Controller API 列表一致，按风险等级过滤文档内容 |
| `riskCode` | 否 | 与 Controller API 列表一致，按风险码过滤文档内容 |
| `limit` | 否 | 文档最多包含多少条路由，默认 12，服务端上限 50 |

响应包含项目 ID、仓库名、生成时间、文档内路由数、当前筛选命中总数、过滤器回显和 `markdown`。Markdown 内容包括项目名、当前风险筛选、HTTP 方法/路径、Controller 方法、源码位置、request/response、参数、Service/Mapper/Repository 调用和风险提示。

### Controller API Docs Snapshots

文档快照用于保留某一次 Controller API Markdown 生成结果，便于跨索引周期审计和回看。

`POST /projects/{id}/controller-apis/docs/snapshots` 使用与 docs 生成接口相同的 `riskLevel`、`riskCode` 和 `limit` 查询参数，生成 Markdown 后保存到 `controller_api_doc_snapshot`，并返回包含 `markdown` 的快照详情。

`GET /projects/{id}/controller-apis/docs/snapshots?limit=5` 返回最近快照摘要，默认 5 条，服务端上限 20。摘要不返回 Markdown 大字段，只返回 `id`、`projectId`、`generatedByUserId`、`repoFullName`、`generatedAt`、`routeCount`、`filteredCount`、`filters` 和 `createdAt`。

`GET /projects/{id}/controller-apis/docs/snapshots/{snapshotId}` 返回单个快照详情，包含完整 `markdown`。`DELETE /projects/{id}/controller-apis/docs/snapshots/{snapshotId}` 删除单个快照，删除后再次读取返回 `CONTROLLER_API_DOC_SNAPSHOT_NOT_FOUND`。`DELETE /projects/{id}/controller-apis/docs/snapshots` 清空当前项目下所有 API 文档快照，并返回 `deletedCount`。所有快照接口都通过项目 owner 校验隔离用户数据。

## 5. Settings API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `GET` | `/settings/coder` | 当前 Coder 模型配置状态 |
| `GET` | `/settings/github` | 当前 GitHub 发布配置状态 |
| `GET` | `/settings/sandbox` | 当前 Docker 沙箱运行时配置状态 |

### GET `/settings/coder`

返回当前运行时 Coder model client 的脱敏配置状态。该接口需要 JWT 鉴权，只用于可见性和排障，不返回 API key、fixture response、organization/project 原始值等敏感内容。

响应：

```json
{
  "mode": "openai-compatible",
  "provider": "OPENAI_COMPATIBLE",
  "enabled": true,
  "ready": true,
  "model": "gpt-test-coder",
  "apiBaseUrl": "https://api.openai.com/v1",
  "apiKeyConfigured": true,
  "fixtureConfigured": false,
  "timeoutSeconds": 120,
  "maxCompletionTokens": 4096,
  "instructionRole": "developer",
  "organizationConfigured": false,
  "projectConfigured": false,
  "missingRequirements": [],
  "supportedModes": ["disabled", "fixture", "openai", "openai-compatible"]
}
```

`ready=false` 时，`missingRequirements` 会列出缺失项，例如 `api-key`、`model`、`fixture-response` 或 `supported-mode`。`disabled` 是默认模式，表示 recipe catalog 和 `SAFE_PLANNING_FALLBACK` 可用，因此 `ready=true` 且 `enabled=false`。

### GET `/settings/github`

返回当前运行时 GitHub PR 发布配置的脱敏状态。该接口需要 JWT 鉴权，只用于可见性和排障，不返回 `GITHUB_TOKEN` 或 `REPOPILOT_GITHUB_TOKEN` 原文。

响应：

```json
{
  "provider": "GITHUB",
  "enabled": false,
  "ready": true,
  "publishMode": "LOCAL_DRAFT_ONLY",
  "apiBaseUrl": "https://api.github.com",
  "tokenConfigured": false,
  "remotePublishingEnabled": false,
  "localDraftMode": true,
  "missingRequirements": []
}
```

`enabled=false` 是本地开发默认模式，表示审批后仍会准备本地 target branch、commit 和 `DRAFT_READY` PR 记录，因此 `ready=true`。配置 `REPOPILOT_GITHUB_ENABLED=true` 后，远程发布需要 `REPOPILOT_GITHUB_TOKEN` 或 `GITHUB_TOKEN`，缺失时 `ready=false` 且 `missingRequirements=["token"]`。

### GET `/settings/sandbox`

返回当前 Docker 沙箱运行时配置和轻量 readiness 检查。该接口需要 JWT 鉴权，只用于可见性和排障，不会启动测试容器或修改工作区。

响应：

```json
{
  "ready": true,
  "dockerImage": "maven:3.9-eclipse-temurin-17",
  "dockerImageConfigured": true,
  "timeoutSeconds": 600,
  "workspaceRoot": "/Users/crossng/Desktop/ai-agent/workspace",
  "workspaceRootExists": true,
  "workspaceRootWritable": true,
  "mavenCachePath": "/Users/crossng/Desktop/ai-agent/.m2",
  "mavenCacheExists": true,
  "mavenCacheWritable": true,
  "dockerCheckEnabled": true,
  "dockerAvailable": true,
  "dockerVersion": "27.5.1",
  "missingRequirements": [],
  "checks": [
    {
      "code": "DOCKER_DAEMON",
      "label": "Docker daemon",
      "status": "PASS",
      "message": "Docker daemon responded with version 27.5.1."
    }
  ]
}
```

`checks.status` 取值为 `PASS`、`WARN` 或 `BLOCKED`。`ready=false` 时，`missingRequirements` 会列出阻塞项，例如 `docker_daemon`、`docker_image`、`workspace_root`、`maven_cache` 或 `timeout`。`REPOPILOT_SANDBOX_DOCKER_CHECK_ENABLED=false` 可关闭 Docker daemon 探测，此时 Docker 检查返回 `WARN`，不阻塞配置 ready。

## 6. Agent Task API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `POST` | `/agent/tasks` | 创建任务 |
| `GET` | `/agent/tasks` | 任务列表 |
| `GET` | `/agent/tasks/{id}` | 任务详情 |
| `POST` | `/agent/tasks/{id}/run` | 启动任务 |
| `POST` | `/agent/tasks/{id}/cancel` | 取消任务 |
| `GET` | `/agent/tasks/{id}/steps` | 执行步骤 |
| `GET` | `/agent/tasks/{id}/run-report` | 当前 run 证据报告 |
| `POST` | `/agent/tasks/{id}/run-report/snapshots` | 保存当前 run 证据报告快照 |
| `GET` | `/agent/tasks/{id}/run-report/snapshots` | 当前任务 run report 快照列表 |
| `GET` | `/agent/tasks/{id}/run-report/snapshots/{snapshotId}` | run report 快照详情 |
| `GET` | `/agent/tasks/{id}/logs` | 执行日志 |
| `GET` | `/agent/tasks/{id}/stream` | SSE 实时事件 |

### POST `/agent/tasks`

请求：

```json
{
  "projectId": 1,
  "taskType": "FEATURE",
  "title": "新增 User 分页查询接口",
  "description": "请给 User 模块新增分页查询接口，保持现有代码风格，并补充测试。"
}
```

响应：

```json
{
  "id": 1001,
  "status": "CREATED"
}
```

### GET `/agent/tasks`

返回当前用户任务列表，默认按创建时间倒序。可选查询参数用于工作台任务筛选；所有过滤都限定在当前登录用户内，不返回其他用户任务。

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `projectId` | 否 | 按项目 ID 过滤 |
| `status` | 否 | 按 `AgentTaskStatus` 精确过滤，例如 `WAITING_HUMAN_APPROVAL` |
| `taskType` | 否 | 按 `FEATURE`、`BUGFIX`、`REVIEW` 或 `DOC` 过滤 |
| `query` | 否 | 对任务标题和描述做大小写不敏感包含搜索 |

示例：

```text
GET /api/agent/tasks?projectId=1&status=WAITING_HUMAN_APPROVAL&taskType=FEATURE&query=pagination
```

响应：

```json
[
  {
    "id": 1001,
    "projectId": 1,
    "taskType": "FEATURE",
    "title": "Add User pagination API",
    "description": "Add a paginated query API for the User module.",
    "status": "WAITING_HUMAN_APPROVAL",
    "currentRunId": 2001,
    "createdAt": "2026-07-15T12:00:00Z"
  }
]
```

### POST `/agent/tasks/{id}/run`

从 `CREATED` 或 `FAILED_TEST` 状态启动任务。接口会创建新的 `agent_run`，将任务切换到 `GENERATING_PATCH`，并把长流程提交到后台执行器；响应返回时 run 通常仍为 `RUNNING`，前端应订阅任务 SSE 事件并通过任务详情、步骤列表、测试记录和审计日志刷新当前进度，轮询作为断线兜底。

如果同一项目已有其他任务处于写入型运行状态，接口返回 `409 PROJECT_WRITE_TASK_RUNNING`，不会创建新的 run。写入型状态包括索引、规划、检索、patch 生成、沙箱应用、测试、修复、审查和 PR 创建。

响应：

```json
{
  "id": 2001,
  "taskId": 1001,
  "status": "RUNNING",
  "startedAt": "2026-07-14T14:18:00Z",
  "finishedAt": null,
  "errorMessage": null
}
```

### POST `/agent/tasks/{id}/cancel`

取消当前任务。接口会立即把任务状态设为 `CANCELLED`；如果任务存在仍在运行的 current run，也会把该 run 标为 `CANCELLED` 并写入完成时间。后台执行循环会在关键阶段读取最新任务状态，发现取消后停止继续生成 patch、运行沙箱测试、自动审查或推进到审批点。

响应：

```json
{
  "id": 1001,
  "status": "CANCELLED",
  "currentRunId": 2001
}
```

### GET `/agent/tasks/{id}/run-report`

返回当前任务 current run 的结构化证据报告。该接口需要 JWT 鉴权，只允许任务 owner 读取；任务还没有 current run 时返回 `AGENT_RUN_NOT_FOUND`。

响应包含任务、项目、run 状态，按 Agent 阶段汇总的 `sections`，以及可直接复制或下载的 Markdown：

```json
{
  "taskId": 1001,
  "runId": 2001,
  "projectId": 1,
  "projectName": "example/demo-spring",
  "taskType": "FEATURE",
  "taskTitle": "Add User pagination API",
  "taskStatus": "WAITING_HUMAN_APPROVAL",
  "runStatus": "SUCCESS",
  "startedAt": "2026-07-15T12:00:00Z",
  "finishedAt": "2026-07-15T12:01:30Z",
  "generatedAt": "2026-07-15T12:02:00Z",
  "sections": [
    {
      "key": "planner",
      "title": "任务规划",
      "stepName": "plan_task",
      "status": "SUCCESS",
      "finishedAt": "2026-07-15T12:00:10Z",
      "summary": "为任务准备实现上下文：Add User pagination API",
      "facts": ["检索词：Add User pagination API, pagination"],
      "highlights": ["1. 检索仓库上下文 - 检索已索引代码片段"]
    }
  ],
  "markdown": "# RepoPilot Agent 运行报告\n\n..."
}
```

`sections` 当前覆盖 planner、retrieval、patch、patch safety、sandbox tests、automated review 和 human approval checkpoint。原始 step/model/tool 审计仍通过 steps、model-calls 和 tool-calls 接口查询；run report 面向演示、分享和快速排查。

### Agent Run Report Snapshots

运行报告快照用于保留某一次 current run report 的 Markdown，避免后续 Regenerate 或重新运行任务替换 current run 后丢失旧报告。

`POST /agent/tasks/{id}/run-report/snapshots` 根据当前 run 生成报告并保存，返回包含 `markdown` 的快照详情。任务还没有 current run 时返回 `AGENT_RUN_NOT_FOUND`。

`GET /agent/tasks/{id}/run-report/snapshots?limit=5` 返回最近快照摘要，默认 5 条，服务端上限 20。摘要不返回 Markdown，只返回 `id`、`taskId`、`runId`、`projectId`、`projectName`、`taskTitle`、`taskType`、`taskStatus`、`runStatus`、run 开始/结束时间、`reportGeneratedAt`、`sectionCount` 和 `createdAt`。

`GET /agent/tasks/{id}/run-report/snapshots/{snapshotId}` 返回单个快照详情，包含完整 `markdown`。所有快照接口都先校验任务 owner，因此不能跨用户读取或保存其他人的 run report。

### GET `/agent/tasks/{id}/stream`

返回 `text/event-stream`。连接建立后会先发送当前任务状态和当前 run 已有 step 的快照事件。若任务正在后台执行，连接会保持打开，并继续推送新的任务状态、step 和完成事件；若任务已处于审批、失败、取消或完成状态，接口会发送快照后立即以 `STREAM_COMPLETE` 结束。

事件数据结构：

```json
{
  "eventType": "STEP_SNAPSHOT",
  "taskId": 1001,
  "taskStatus": "WAITING_HUMAN_APPROVAL",
  "runId": 2001,
  "runStatus": "SUCCESS",
  "stepId": 5001,
  "stepName": "run_tests",
  "stepStatus": "SUCCESS",
  "message": "run_tests SUCCESS",
  "createdAt": "2026-07-14T13:49:00Z"
}
```

当前事件类型：

- `TASK_SNAPSHOT`
- `STEP_SNAPSHOT`
- `TASK_UPDATED`
- `STEP_RECORDED`
- `STREAM_COMPLETE`

## 7. Patch 与审批 API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `GET` | `/tasks/{id}/patches` | patch 列表 |
| `GET` | `/tasks/{id}/patches/latest` | 最新 patch |
| `POST` | `/tasks/{id}/patches/regenerate` | 重新生成 patch 并重新进入沙箱测试 |
| `POST` | `/tasks/{id}/patch/regenerate` | 兼容旧路径，行为同上 |
| `POST` | `/tasks/{id}/approval/approve` | 审批通过 |
| `POST` | `/tasks/{id}/approval/reject` | 拒绝 |
| `GET` | `/tasks/{id}/approval` | 审批记录 |

### GET `/tasks/{id}/patches/latest`

响应：

```json
{
  "id": 3001,
  "agentTaskId": 1001,
  "agentRunId": 2001,
  "baseBranch": "main",
  "targetBranch": "repopilot/task-1001",
  "status": "APPLIED",
  "summary": "新增 GET /api/users/page，并补齐 Service/Mapper 分页逻辑和单元测试。",
  "generationMode": "SPRING_USER_PAGINATION_RECIPE",
  "changedFiles": [
    {
      "path": "src/main/java/com/example/demo/user/UserController.java",
      "oldPath": "src/main/java/com/example/demo/user/UserController.java",
      "changeType": "MODIFIED",
      "addedLines": 39,
      "deletedLines": 30
    },
    {
      "path": "src/test/java/com/example/demo/user/UserServiceTest.java",
      "oldPath": null,
      "changeType": "ADDED",
      "addedLines": 36,
      "deletedLines": 0
    }
  ]
}
```

### POST `/tasks/{id}/patches/regenerate`

从 `WAITING_HUMAN_APPROVAL`、`FAILED_TEST`、`FAILED_PATCH_GENERATION` 或 `CANCELLED` 状态重新生成 patch。系统会创建新的 `agent_run`，重新执行检索、patch 生成、沙箱应用、`mvn -q test` 和自动审查；成功后任务回到 `WAITING_HUMAN_APPROVAL`，新的 run 标记为 `SUCCESS`。

如果同一项目已有其他写入型任务正在运行，接口返回 `409 PROJECT_WRITE_TASK_RUNNING`。

响应为最新 patch：

```json
{
  "id": 3002,
  "agentTaskId": 1001,
  "agentRunId": 2002,
  "baseBranch": "main",
  "targetBranch": "repopilot/task-1001",
  "status": "APPLIED",
  "summary": "新增 GET /api/users/page，并补齐 Service/Mapper 分页逻辑和单元测试。",
  "generationMode": "SPRING_USER_PAGINATION_RECIPE",
  "changedFiles": [
    {
      "path": "src/main/java/com/example/demo/user/UserController.java",
      "oldPath": "src/main/java/com/example/demo/user/UserController.java",
      "changeType": "MODIFIED",
      "addedLines": 39,
      "deletedLines": 30
    }
  ]
}
```

### POST `/tasks/{id}/approval/approve`

请求：

```json
{
  "patchId": 3001,
  "comment": "测试通过，同意创建 PR"
}
```

约束：

- 任务必须处于 `WAITING_HUMAN_APPROVAL`。
- patch 必须属于该任务。
- patch 状态必须是 `GENERATED` 或 `APPLIED`。
- 同一项目不能有其他写入型任务正在运行，否则返回 `409 PROJECT_WRITE_TASK_RUNNING`。

响应：

```json
{
  "id": 5001,
  "agentTaskId": 1001,
  "patchId": 3001,
  "action": "APPROVE",
  "patchStatus": "APPROVED",
  "taskStatus": "CREATING_PULL_REQUEST"
}
```

## 8. Pull Request API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `POST` | `/tasks/{id}/pull-request` | 准备 PR 记录 |
| `GET` | `/tasks/{id}/pull-request` | 查询最新 PR 记录 |
| `GET` | `/tasks/{id}/pull-request/preflight` | 查询 PR 发布前置检查 |

当前实现会在 patch 审批通过且沙箱测试通过后创建本地 target branch、应用 diff、生成 commit，并写入 `DRAFT_READY` 记录，同时把任务标记为 `DONE` 以释放项目写入槽。配置 `REPOPILOT_GITHUB_ENABLED=true` 且提供 `REPOPILOT_GITHUB_TOKEN` 或 `GITHUB_TOKEN` 后，会继续 push target branch、调用 GitHub API 创建 PR，并把记录更新为 `OPEN`。

响应：

```json
{
  "id": 4001,
  "agentTaskId": 1001,
  "patchId": 3001,
  "provider": "GITHUB",
  "prNumber": null,
  "url": null,
  "title": "RepoPilot: 新增 User 分页查询接口",
  "baseBranch": "main",
  "targetBranch": "repopilot/task-1001",
  "commitSha": "8e2f1a5...",
  "commitMessage": "RepoPilot: 新增 User 分页查询接口\n\nGenerated by RepoPilot.\n\nTask: #1001\nRun: #2001\nPatch: #3001\nTests: mvn test passed",
  "status": "DRAFT_READY",
  "remotePushedAt": null,
  "openedAt": null,
  "errorMessage": null,
  "taskStatus": "DONE"
}
```

约束：

- 任务必须处于 `CREATING_PULL_REQUEST`；从 `FAILED_PR_CREATION` 重试也允许。若任务已是 `DONE` 且已有 PR 记录，重复调用会直接返回已有记录。
- 最新 patch 必须为 `APPROVED`。
- 最新 patch 对应的最新 `test_run` 必须为 `PASSED`。
- 项目本地仓库工作区必须干净。
- 同一项目不能有其他写入型任务正在运行，否则返回 `409 PROJECT_WRITE_TASK_RUNNING`。
- 未开启 GitHub 发布时，记录停留在 `DRAFT_READY`，`url` 和 `prNumber` 为空，任务状态为 `DONE`。

### GET `/tasks/{id}/pull-request/preflight`

返回 PR 准备前的只读检查结果，用于前端解释“是否可以准备 PR、若不能卡在哪一步”。该接口不会创建分支、commit 或远程 PR，也不会返回 GitHub token。

响应：

```json
{
  "taskId": 1001,
  "taskStatus": "CREATING_PULL_REQUEST",
  "canPrepare": true,
  "publishMode": "LOCAL_DRAFT_ONLY",
  "localDraftReady": true,
  "remotePublishingEnabled": false,
  "remotePublishingWillRun": false,
  "remoteReady": true,
  "repositoryEligible": false,
  "tokenConfigured": false,
  "latestPatchStatus": "APPROVED",
  "latestTestStatus": "PASSED",
  "existingPullRequestStatus": null,
  "checks": [
    {
      "code": "TASK_STATUS",
      "label": "Task state",
      "status": "PASS",
      "message": "Task is ready to prepare a pull request."
    },
    {
      "code": "REMOTE_GITHUB",
      "label": "Remote GitHub",
      "status": "WARN",
      "message": "Remote GitHub publishing is disabled; RepoPilot will stop at DRAFT_READY."
    }
  ],
  "blockers": []
}
```

`checks.status` 取值为 `PASS`、`PENDING`、`BLOCKED` 或 `WARN`。`blockers` 只包含阻止本次准备 PR 的 `BLOCKED`/`PENDING` 信息；`WARN` 表示系统会继续本地草稿流程，例如远程 GitHub 发布关闭或项目不是 `github.com` 仓库。

## 9. Trace API

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| `GET` | `/agent/runs/{runId}/tool-calls` | 工具调用日志 |
| `GET` | `/agent/runs/{runId}/model-calls` | 模型调用日志 |
| `GET` | `/agent/runs/{runId}/test-runs` | 测试执行记录 |

### GET `/agent/runs/{runId}/test-runs`

响应：

```json
[
  {
    "id": 7001,
    "agentRunId": 2001,
    "patchId": 3001,
    "command": "docker run --rm ... maven:3.9-eclipse-temurin-17 sh -lc mvn -q test",
    "exitCode": 0,
    "durationMs": 12840,
    "logExcerpt": "",
    "status": "PASSED",
    "createdAt": "2026-07-09T13:30:00Z"
  }
]
```

### GET `/agent/runs/{runId}/tool-calls`

响应：

```json
[
  {
    "id": 9001,
    "agentRunId": 2001,
    "toolName": "search_code",
    "inputJson": "{\"projectId\":1,\"queries\":[\"UserService\"],\"limitPerQuery\":8}",
    "outputJson": "{\"uniqueResultCount\":4,\"chunkIds\":[101,102]}",
    "status": "SUCCESS",
    "durationMs": 42,
    "errorMessage": null,
    "startedAt": "2026-07-14T10:00:00Z",
    "finishedAt": "2026-07-14T10:00:00Z"
  }
]
```

约束：

- 只能查询当前用户所属 task 的 run。
- `inputJson` 中的 token、secret、password、authorization 等敏感字段必须脱敏。
- `outputJson` 保存摘要；超大 JSON 会被截断为带 `truncated=true` 的预览对象。

### GET `/agent/runs/{runId}/model-calls`

响应：

```json
[
  {
    "id": 9101,
    "agentRunId": 2001,
    "stepName": "plan_task",
    "modelProvider": "LOCAL_PLACEHOLDER",
    "modelName": "deterministic-mvp",
    "promptJson": "{\"title\":\"新增 User 分页查询接口\"}",
    "responseJson": "{\"summary\":\"为任务准备实现上下文\"}",
    "status": "SUCCESS",
    "promptTokens": 12,
    "completionTokens": 20,
    "totalTokens": 32,
    "durationMs": 18,
    "errorMessage": null,
    "startedAt": "2026-07-14T10:00:00Z",
    "finishedAt": "2026-07-14T10:00:00Z"
  }
]
```

约束：

- 只能查询当前用户所属 task 的 run。
- `promptJson` 中的 token、secret、password、authorization、api key 等敏感字段必须脱敏。
- MVP 未接真实模型前使用 `LOCAL_PLACEHOLDER / deterministic-mvp` 记录规划、patch 生成和审查等模型型步骤。
- Token 计数在本地占位模式下为估算值，接入真实模型后应替换为供应商返回值。

## 10. 错误码

| 错误码 | HTTP 状态 | 说明 |
| --- | --- | --- |
| `AUTH_INVALID_TOKEN` | 401 | Token 无效 |
| `PROJECT_NOT_FOUND` | 404 | 项目不存在 |
| `PROJECT_CLONE_FAILED` | 500 | clone 失败 |
| `INDEX_PARSE_FAILED` | 500 | AST 解析失败 |
| `AGENT_TASK_NOT_FOUND` | 404 | 任务不存在 |
| `AGENT_INVALID_STATUS` | 409 | 当前状态不允许操作 |
| `PROJECT_WRITE_TASK_RUNNING` | 409 | 同一项目已有其他写入型任务正在运行 |
| `PATCH_NOT_APPROVED` | 409 | patch 未审批 |
| `PULL_REQUEST_NOT_FOUND` | 404 | PR 记录不存在 |
| `PATCH_TEST_NOT_FOUND` | 409 | patch 缺少沙箱测试记录 |
| `PATCH_TEST_NOT_PASSED` | 409 | patch 最新沙箱测试未通过 |
| `PULL_REQUEST_WORKTREE_DIRTY` | 409 | 项目本地仓库存在未提交变更 |
| `PULL_REQUEST_BRANCH_EXISTS` | 409 | 本地 target branch 已存在 |
| `PULL_REQUEST_LOCAL_GIT_NOT_READY` | 409 | PR 记录缺少本地 branch/commit 信息 |
| `PROJECT_NOT_GITHUB_REPOSITORY` | 409 | 项目不是 github.com 仓库 |
| `GITHUB_TOKEN_NOT_CONFIGURED` | 409 | 已开启 GitHub 发布但未配置 token |
| `GITHUB_BRANCH_PUSH_FAILED` | 409 | target branch push 失败 |
| `SANDBOX_PREPARE_FAILED` | 500 | 沙箱工作区准备失败 |
| `SANDBOX_INVALID_PATH` | 500 | 沙箱路径越界 |
| `SANDBOX_TEST_FAILED` | 422 | 测试失败 |
| `GITHUB_PR_CREATE_FAILED` | 502 | PR 创建失败 |
