# 前端页面规格

## 1. 前端目标

前端是 RepoPilot 的任务控制台，重点不是营销展示，而是让用户高效完成仓库接入、任务创建、Agent 过程观察、diff 审批和 PR 查看。

## 2. 页面清单

| 页面 | 路由建议 | 核心功能 |
| --- | --- | --- |
| 登录页 | `/login` | 登录、错误提示 |
| 工作台概览 | `/` 或 `#overview` | 当前用户项目、任务、待审批、失败、PR 计数、最近 run 表现和跨项目活动流 |
| 项目列表页 | `/projects` | 按状态和仓库关键词筛选项目列表，查看状态与最近索引时间 |
| 添加项目页 | `/projects/new` | 输入 repo URL、token、默认分支 |
| 项目详情页 | `/projects/:id` | 文件树、项目摘要、符号统计、Controller API |
| 代码检索页 | `/projects/:id/search` | 输入问题，查看相关代码 chunk |
| 任务列表页 | `/agent/tasks` | 按项目、状态、类型和标题/描述关键词筛选 |
| 创建任务页 | `/projects/:id/tasks/new` | 选择任务类型，输入自然语言需求 |
| Agent 执行页 | `/agent/tasks/:id` | 状态流、步骤、日志、计划、检索结果 |
| Diff 预览页 | `/agent/tasks/:id/patch` | 文件级 diff、摘要、风险提示 |
| 测试日志页 | `/agent/tasks/:id/tests` | Maven 命令、退出码、日志摘要 |
| 审批页 | `/agent/tasks/:id/approval` | Approve、Reject、Regenerate |
| PR 结果页 | `/agent/tasks/:id/pull-request` | PR 链接、状态、标题、描述 |
| 工具调用追踪页 | `/agent/runs/:runId/tool-calls` | tool call 输入输出、耗时、状态 |
| 系统配置页 | `/settings` | 模型、GitHub、Docker、向量库配置；MVP 控制台先内嵌 Coder、GitHub 发布与 Sandbox 运行时只读状态 |

## 3. 关键用户流程

### 3.1 接入仓库

```text
项目列表
  -> 添加项目
  -> 输入 repo URL/token/default branch
  -> clone 中
  -> clone 成功
  -> 进入项目详情
  -> 触发索引
```

### 3.2 创建并执行 Agent 任务

```text
项目详情
  -> 创建任务
  -> 输入任务描述
  -> 启动任务
  -> 查看执行页
  -> 查看计划和检索结果
  -> 查看 diff 和测试结果
  -> 审批
  -> 创建 PR
```

## 4. 页面状态

每个核心页面至少处理：

- Loading。
- Empty。
- Error。
- Unauthorized。
- Running。
- Failed。
- Success。

Agent 执行页必须特别处理长任务状态：

- 当前步骤高亮。
- 已完成步骤可展开。
- 失败步骤展示错误。
- 运行中任务通过 `GET /api/agent/tasks/{id}/stream` SSE 实时刷新状态、步骤、patch、测试和审计面板。
- 日志流中断时显示 fallback 状态，并继续通过任务详情接口轮询。
- 任务详情应把 `agent_step.inputJson`/`outputJson` 的稳定字段解析为 `AgentEvidencePanel`，在原始审计 JSON 之外展示计划摘要、检索命中、patch 生成结果、patch 安全门、沙箱测试结果、自动审查和人工审批 checkpoint。
- 任务存在 current run 时，任务详情应通过 `GET /api/agent/tasks/{id}/run-report` 加载可复制/下载的中文 Markdown run report；没有 current run 时按钮禁用并保持空状态。任务详情还应通过 `GET /api/agent/tasks/{id}/run-report/snapshots?limit=5` 展示最近运行报告快照，可通过 `POST /api/agent/tasks/{id}/run-report/snapshots` 保存当前报告，并从历史快照复制或下载保存时的 Markdown。
- 任务详情主链路的导航、状态卡、步骤时间线、Agent 证据、模型/工具审计、补丁、沙箱测试、人工审批和 PR 前置检查使用中文产品文案；运行报告 Markdown 的标题、段落、事实和重点使用中文，后端枚举、step name、recipe id、命令和路径保留工程原文，便于排查和对接 API。
- 工作台概览、Agent 运行表现、最近任务活动、Coder 配置、GitHub 发布配置和沙箱运行时配置使用中文产品文案；配置枚举、provider、mode 和 readiness badge 保留工程原文，便于和环境变量、后端响应对应。
- 登录、项目接入、项目筛选、任务创建、任务筛选、仓库洞察、Controller API 风险视图、接口文档快照和代码搜索使用中文产品文案；仓库名、Java 符号、HTTP 方法、风险码、后端状态枚举和 Controller API Markdown 内容保留工程原文，便于和代码及 API 响应一一对应。

## 5. 核心组件

| 组件 | 用途 |
| --- | --- |
| `DashboardSummaryPanel` | 用中文指标卡展示当前用户工作区项目、任务运行态、待审批、失败、完成和 PR 草稿/打开计数，并可复制包含 Dashboard 窗口状态的概览链接 |
| `DashboardRunMetricsPanel` | 用中文指标卡展示当前用户最近 7/14/30 天 Agent run 总数、成功率、平均耗时、运行中数量和每日趋势，窗口选择通过 `runMetricsDays` URL 参数恢复 |
| `DashboardActivityPanel` | 用中文标题和空状态展示当前用户最近 10/25/50 条 Agent step 活动，包含项目、任务、step、状态和发生时间，数量选择通过 `activityLimit` URL 参数恢复 |
| `ProjectStatusBadge` | 展示项目 clone/index 状态 |
| `ProjectFilterForm` | 用中文标签按项目状态和 repo 名称/URL 关键词筛选 `GET /api/projects` 结果，支持一键重置、显示项目数量和复制当前项目视图链接 |
| `FileTree` | 用中文标题、空状态和目录/文件说明展示仓库文件树 |
| `SymbolSummary` | 用中文标题、空状态和索引数量说明展示 Controller、Service、Mapper、Entity 数量 |
| `ControllerApiList` | 用中文标签展示 Spring Controller 路由、HTTP 方法、参数来源、Service 与 Mapper/Repository 调用、风险分、鉴权/校验/参数边界风险提示、字段/参数级风险细节、风险等级计数、风险等级/风险码筛选、可复制风险视图/单路由链接、可复制/下载/保存由后端生成的当前可见 API Markdown 文档、最近 API 文档快照及快照复制/下载/删除/清空操作、请求/响应类型和源码位置；Markdown 内容仍由后端生成并保留工程原文 |
| `AgentStepTimeline` | 以中文标题展示 Agent 状态机步骤和运行中状态 |
| `AgentEvidencePanel` | 当前任务存在后端 run report 时优先渲染 report sections，用中文证据卡展示规划、检索、补丁、安全门、测试、审查、人工审批检查点以及 `Worker 重试恢复证据` 等后端诊断段落；没有 run report 时从 step JSON 中提取 planner、retriever、Coder、sandbox、review 和 approval checkpoint 证据；补丁证据展示 `generationMode`、`generationProvider` 和模型名；并支持复制、下载或保存后端生成的 Markdown run report；最近运行报告快照支持复制和下载 |
| `TaskFilterForm` | 用中文标签按项目、状态、类型和关键词筛选 `GET /api/agent/tasks` 结果，支持一键重置、显示任务数量和复制当前任务视图链接 |
| `ToolCallTable` | 展示工具调用审计 |
| `DiffViewer` | 展示 unified diff、文件级摘要、patch generation mode、生成来源和模型名 |
| `TestLogPanel` | 展示 Maven 测试日志 |
| `ApprovalActions` | 提供通过审批、拒绝和重新生成操作 |
| `PrResultCard` | 用中文标签展示 PR 链接、分支、提交、打开状态和正文；远端发布失败时展示中文失败类型、原因、下一步和原始错误 |
| `PullRequestPreflightSummary` | 用中文标签展示 PR 发布前置检查、发布模式、本地草稿状态、远程 GitHub 状态和 blocker |
| `ToolCallAuditPanel` | 展示工具调用输入、输出摘要、状态和耗时 |
| `ModelCallAuditPanel` | 展示模型调用提示词、响应摘要、模型名、token 和耗时 |
| `DemoReadinessPanel` | 用中文 checklist 汇总本地闭环演示、真实模型演示和远端 GitHub PR 演示是否就绪，展示缺失环境变量名但不展示任何密钥 |
| `CoderSettingsPanel` | 用中文标签展示当前 Coder mode、provider、model、API base URL、key 是否配置、fixture 是否配置、缺失配置项和支持模式；不展示任何密钥或 fixture 原文 |
| `GitHubSettingsPanel` | 用中文标签展示当前 GitHub PR 发布模式、provider、API base URL、token 是否配置、远程发布是否启用和缺失配置项；不展示 token 原文 |
| `SandboxSettingsPanel` | 用中文标签展示 Docker daemon、sandbox image、Maven cache、workspace root、timeout 和 readiness 检查；不启动测试容器 |

## 6. 交互约束

- patch 未生成时不展示审批按钮。
- patch 未通过测试时 Approve 需要二次确认。
- patch 未审批时创建 PR 按钮禁用。
- PR 准备按钮应以 `GET /api/tasks/{id}/pull-request/preflight` 返回的 `canPrepare` 为准；PR 面板展示 `PASS`、`PENDING`、`BLOCKED`、`WARN` 检查项，以及未审批、测试未过、GitHub token 缺失等 blocker；任务或 PR 记录处于远端发布失败状态时，按钮文案切换为“重试发布 PR”，PR 面板解释 token、push 或 GitHub API 失败原因并保留原始错误。
- 任务运行中禁止重复启动同一任务。
- 项目列表筛选应通过 `GET /api/projects` 的 `status` 和 `query` 查询参数执行；筛选表单点击 `应用筛选` 后请求后端，`重置` 清空筛选并重新加载项目行。控制台应保留完整项目列表用于创建任务、任务筛选和任务详情项目名展示，避免项目列表筛选影响任务操作上下文。
- 项目视图应通过应用 URL query 恢复当前项目列表筛选和洞察项目：`projectStatus` 对应项目 API 的 `status`，`projectQuery` 对应项目 API 的 `query`，`projectId` 对应 Repository insight 当前项目。URL 同步应保留 Controller API 风险筛选参数和 hash。
- 项目筛选表单应提供 `复制项目视图链接` 操作，将当前项目筛选和 `projectId` 写入剪贴板，并通过 `aria-live` 状态提示复制成功或不可用。
- 任务列表筛选应通过 `GET /api/agent/tasks` 的 `projectId`、`status`、`taskType` 和 `query` 查询参数执行；筛选表单点击 `应用筛选` 后请求后端，`重置` 清空筛选并重新加载任务。运行中任务的轮询和 SSE 刷新应继续使用当前筛选条件。
- 任务视图应通过应用 URL query 恢复当前任务筛选和任务详情：`taskProjectId` 对应任务 API 的 `projectId`，`taskStatus` 对应 `status`，`taskType` 对应 `taskType`，`taskQuery` 对应 `query`，`taskId` 对应当前任务详情。URL 同步应保留项目视图、Controller API 风险筛选参数和 hash。
- 任务筛选表单应提供 `复制任务视图链接` 操作，将当前任务筛选和 `taskId` 写入剪贴板，并通过 `aria-live` 状态提示复制成功或不可用。
- 任务运行中展示实时 stream 状态；断线后保留轮询刷新。
- Reject 必须填写原因。
- Controller API 风险筛选应支持按风险等级和风险码组合过滤，筛选变化应触发后端 `riskLevel`/`riskCode` 查询参数，基于 API `filteredCount` 展示过滤后的路由数量，通过 URL query 恢复筛选状态，基于 API `riskSummary` 展示 HIGH/MEDIUM/LOW/NONE 风险等级计数，基于 API `riskCodes` 展示全量风险码选项，点击计数可快速筛选对应等级，并允许复制当前风险视图链接、单条 Controller API 路由链接，或复制/下载/保存当前可见 API 的 Markdown 文档。Markdown 文档应由 `GET /api/projects/{id}/controller-apis/docs?riskLevel=...&riskCode=...&limit=...` 生成，包含项目名、当前筛选、HTTP 方法/路径、Controller 方法、参数、request/response、调用链、风险提示和源码位置；下载文件名应包含 `controller-api-docs` 且使用 `.md` 后缀；保存快照应调用 `POST /api/projects/{id}/controller-apis/docs/snapshots?riskLevel=...&riskCode=...&limit=...`，项目洞察加载时应通过 `GET /api/projects/{id}/controller-apis/docs/snapshots?limit=5` 展示最近快照摘要；复制或下载历史快照应先读取 `GET /api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}`，确保使用保存时的 Markdown 内容而不是当前重新生成内容；删除历史快照应调用 `DELETE /api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}` 并从最近快照列表即时移除；清空历史快照应调用 `DELETE /api/projects/{id}/controller-apis/docs/snapshots`，展示删除数量并清空最近快照列表。
- Coder 配置状态应通过 `GET /api/settings/coder` 加载，和项目/任务列表并行请求；前端只展示脱敏字段与缺失配置项，不允许展示 API key、fixture response 或 organization/project 原文。
- GitHub 发布配置状态应通过 `GET /api/settings/github` 加载，和项目/任务列表、Coder 配置并行请求；前端只展示 `LOCAL_DRAFT_ONLY`/`REMOTE_GITHUB_PR`、endpoint、token 是否配置和缺失项，不允许展示 token 原文。
- Sandbox 运行时配置状态应通过 `GET /api/settings/sandbox` 加载，和项目/任务列表、Coder/GitHub 配置并行请求；前端展示 Docker daemon、image、timeout、Maven cache 与 workspace readiness，不触发容器执行。
- 演示就绪总览应只从 Coder/GitHub/Sandbox 三个脱敏配置响应派生，不新增密钥读取；默认本地环境展示“本地闭环演示：可演示”“真实模型演示：可选增强”“远端 GitHub PR：本地草稿”，真实 token 环境下自动切换为可演示或需要配置。
- 工作台概览应通过 `GET /api/dashboard/summary`、`GET /api/dashboard/run-metrics?days=...` 和 `GET /api/dashboard/activity?limit=...` 加载，和项目/任务列表、Coder/GitHub/Sandbox 配置并行请求；创建项目、索引完成、创建任务、任务运行状态变化、审批和 PR 准备后应刷新概览指标。概览只展示当前用户聚合数据，不暴露其他用户项目或任务信息。Run metrics 默认最近 7 天，可切换 14/30 天并通过 `runMetricsDays` 恢复当前窗口；Activity 默认最近 10 条，可切换 25/50 条并通过 `activityLimit` 恢复当前数量窗口；overview 提供复制链接操作，写入当前 `runMetricsDays`、`activityLimit` 和 `#overview` 锚点；二者都不阻塞任务详情渲染。
- Regenerate 可以附带用户反馈。

## 7. MVP 页面优先级

| 优先级 | 页面 |
| --- | --- |
| P0 | 登录页、项目列表页、添加项目页、项目详情页、创建任务页、Agent 执行页、Diff 预览页、审批页、PR 结果页 |
| P1 | 代码检索页、测试日志页、工具调用追踪页、系统配置页 |
