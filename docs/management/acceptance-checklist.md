# 验收清单

## 1. 文档验收

| 项 | 状态标准 |
| --- | --- |
| PRD | 明确目标用户、核心场景、功能需求、非功能需求 |
| MVP 范围 | 明确 P0、P1、暂缓范围和演示标准 |
| 用户故事 | 覆盖登录、项目接入、任务、diff、测试、审批、PR |
| 架构文档 | 明确服务边界、数据流、安全边界和部署视图 |
| 后端模块文档 | 明确包结构、模块职责、核心领域对象 |
| Agent 文档 | 明确角色、状态机、Graph 节点、重试和审批暂停点 |
| MCP 工具文档 | 明确工具输入输出、安全约束和审计 |
| API 文档 | 覆盖 Auth、Dashboard、Project、Agent、Patch、Approval、PR、Trace |
| 数据库文档 | 覆盖核心表、字段、索引和保留策略 |
| 沙箱与 GitHub 文档 | 覆盖工作区、Docker、分支、PR、失败处理 |

## 2. MVP 功能验收

| 编号 | 验收项 | 证据 |
| --- | --- | --- |
| AC-001 | 用户可以注册登录 | API 调用成功，JWT 返回 |
| AC-002 | 用户可以添加 GitHub 仓库 | `project.status=READY` |
| AC-003 | 系统可以扫描 Java 项目结构 | `code_file` 和 `code_symbol` 有数据 |
| AC-003b | 系统可以识别 Spring Controller API | `GET /api/projects/{id}/controller-apis` 返回 `items`、`filteredCount`、`riskSummary`、`riskCodes` 和 `filters`，并支持 `riskLevel`/`riskCode` 查询参数过滤 `items`；`items` 包含 method、path、参数来源、必填/默认值、Service 与 Mapper/Repository 调用、风险分、鉴权/校验/参数边界风险提示、字段/参数级风险细节、request/response 类型和源码位置；`filteredCount` 表示当前筛选命中总数；`riskSummary.byLevel` 汇总 HIGH/MEDIUM/LOW/NONE 全量数量；`riskCodes` 汇总全量风险码选项；`filters` 回显服务端规范化后的当前筛选；前端展示风险等级计数，可点击计数快速触发服务端筛选，可按风险等级和风险码过滤，可通过 URL query 恢复筛选，并可复制当前风险视图或单条路由链接 |
| AC-003c | Controller API 文档可复制/下载/留档 | `GET /api/projects/{id}/controller-apis/docs` 需要鉴权，支持 `riskLevel`、`riskCode` 和 `limit`，只读取当前用户项目，返回项目名、生成时间、routeCount、filteredCount、过滤器回显和 Markdown；`POST /api/projects/{id}/controller-apis/docs/snapshots` 可保存当前筛选的 Markdown 快照，`GET /api/projects/{id}/controller-apis/docs/snapshots` 可列出最近快照摘要，`GET /api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}` 可读取历史快照 Markdown，`DELETE /api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}` 可删除当前用户项目内的历史快照，`DELETE /api/projects/{id}/controller-apis/docs/snapshots` 可清空当前用户项目内所有历史快照并返回删除数量，所有快照接口只允许访问当前用户项目；`ControllerApiList` 提供 `Copy API docs`、`Download API docs` 和 `Save API docs snapshot`，并展示最近快照，历史快照可直接复制、下载、删除或批量清空；文档包含项目名、当前风险筛选、HTTP 方法/路径、Controller 方法、参数、request/response、Service/Mapper/Repository 调用、风险提示和源码位置，并显示可访问的复制/下载/保存/删除/清空状态提示 |
| AC-004 | 系统可以检索相关代码 | `search_code` 返回相关 chunk |
| AC-005 | 用户可以创建 Agent 任务 | `agent_task.status=CREATED` |
| AC-005b | 用户可以取消运行中的 Agent 任务 | `POST /api/agent/tasks/{id}/cancel` 将 task 和 current run 标为 `CANCELLED`；后台执行循环在关键阶段检查取消并停止后续 patch/测试/审查/审批推进；前端任务详情在运行中显示 Cancel |
| AC-005c | 同项目写入任务互斥 | 当同一项目已有其他任务处于索引、规划、检索、patch 生成、沙箱应用、测试、修复、审查或 PR 创建状态时，启动任务、Regenerate、Approve 或准备 PR 返回 `409 PROJECT_WRITE_TASK_RUNNING`；当前任务自身推进不被阻塞 |
| AC-006 | Agent 可以输出计划 | `agent_step.step_name=plan_task` 有结构化输出 |
| AC-006b | Agent 运行证据可读 | `GET /api/agent/tasks/{id}/run-report` 需要鉴权，只允许任务 owner 读取 current run 证据，返回 planner、retrieval、patch、patch safety、sandbox tests、automated review 和 human approval checkpoint sections 以及中文 Markdown；`POST /api/agent/tasks/{id}/run-report/snapshots` 可保存当前报告快照，`GET /api/agent/tasks/{id}/run-report/snapshots` 可列出最近快照摘要，`GET /api/agent/tasks/{id}/run-report/snapshots/{snapshotId}` 可读取历史快照 Markdown，所有快照接口只允许任务 owner 访问；前端任务详情展示 `Agent evidence`，从当前 run 的 step JSON 中解析并展示 `任务规划`、`检索到的代码上下文`、`生成的补丁产物`、`补丁安全门`、`沙箱测试结果`、`自动补丁审查` 和 `人工审批检查点`，补丁证据包含 generation mode、provider 和 model，并支持复制/下载当前报告、保存运行报告快照、复制/下载历史快照；step name、枚举、recipe id、命令和原始 model/tool audit 仍保留工程原文 |
| AC-007 | Agent 可以生成 diff | 默认分页 demo 任务生成真实 unified diff，包含 `GET /api/users/page`、Service/Mapper 分页逻辑、`spring-boot-starter-test` 和 `UserServiceTest`，patch `generationMode=SPRING_USER_PAGINATION_RECIPE` 且 `generationProvider=LOCAL_RECIPE_CATALOG`；User id 参数校验 demo 任务生成真实 guard 与单元测试 diff，patch `generationMode=SPRING_USER_ID_VALIDATION_RECIPE`；User count demo 任务生成真实 `GET /api/users/count`、Service/Mapper count 逻辑和单元测试，patch `generationMode=SPRING_USER_COUNT_RECIPE`；User create demo 任务生成真实 `POST /api/users`、`CreateUserRequest`、Service/Mapper create 逻辑和单元测试，patch `generationMode=SPRING_USER_CREATE_RECIPE`；LLM Coder draft 入口解析 raw response 后生成 `generationMode=LLM_CODER_DRAFT` patch，且只接受 raw unified diff 或单个 diff 代码块；`fixture` 模式已有生产状态机级测试，验证 raw diff 会经过 parser、安全预检、Docker 沙箱 `mvn test`、review 和人工审批暂停点，并在 patch、step output 和 model call audit 中展示 `LOCAL_FIXTURE / fixture-coder`；`openai-compatible` Coder 模式已有本地 HTTP Chat Completions stub 的生产状态机级测试，断言 Authorization header、模型请求体包含检索上下文和 diff-only prompt，raw diff 会复用同一 parser、安全预检、沙箱测试、review 和人工审批链路，并在 patch、step output 和 model call audit 中展示 `OPENAI_COMPATIBLE / gpt-repopilot-test`；patch API 返回 `changedFiles` 文件级变更摘要和增删行数，以及 `generationProvider`/`generationModel`；未知任务回退为 `SAFE_PLANNING_FALLBACK` retrieval-grounded Coder plan diff，包含检索候选文件、符号、行号、编辑顺序和验证门槛；Regenerate 会创建新的 Agent run 和 patch 版本 |
| AC-008 | 系统可以在沙箱运行测试 | `test_run` 记录 exit code 和日志；沙箱应用前执行 `validate_patch_safety`，拒绝路径穿越、绝对路径、保留目录和二进制 patch；分页 demo patch、User id 参数校验 demo patch、User count demo patch 与 User create demo patch 都在沙箱中执行 `mvn -q test` 通过 |
| AC-009 | 未审批不能创建 PR | API 返回 `PATCH_NOT_APPROVED` |
| AC-010 | 审批后可以准备 PR | 未开启 GitHub 发布时生成 `pull_request_record.status=DRAFT_READY`、target branch 和 commit，并将 task 标为 `DONE` 释放项目写入槽；开启 GitHub 发布并提供 token 后生成 `OPEN` 记录且 `pull_request_record.url` 可打开；远端发布路径通过本地 bare Git origin + GitHub API stub 验证真实 `git push`、PR 请求体和失败记录重试 |
| AC-010b | PR 发布前置检查可见 | `GET /api/tasks/{id}/pull-request/preflight` 需要鉴权，返回 task、patch、test、local draft、remote GitHub 的 `PASS`/`PENDING`/`BLOCKED`/`WARN` 检查项和中文 label/message/blockers；前端 PR 面板展示 preflight，审批前显示“需要先审批已测试通过的补丁”的 blocker，审批且测试通过后显示本地 branch/commit 可准备，准备完成后显示已有 `DRAFT_READY` 记录；远端发布失败时展示中文失败解释、下一步、原始错误，并把任务详情按钮切换为“重试发布 PR” |
| AC-011 | 工具调用可审计 | `tool_call_log` 可按 run 查询，`GET /api/agent/runs/{runId}/tool-calls` 返回脱敏输入、输出摘要、状态和耗时 |
| AC-011b | 模型调用可追踪 | `model_call_log` 可按 run 查询，`GET /api/agent/runs/{runId}/model-calls` 返回脱敏 prompt、response 摘要、模型名、token 和耗时 |
| AC-011c | Patch 风险审查可见 | `review_patch` step 输出 `riskLevel`、`summary` 和 findings；前端 Patch 面板展示 `Automated review`，包含新增接口缺鉴权、分页边界和测试覆盖提示 |
| AC-011d | RepairAgent 修复循环可追踪 | Maven 测试因缺少测试依赖或常见 Java 标准库缺 import 编译失败时，任务进入 `REPAIRING`，`repair_patch` step/model call 生成补充 `spring-boot-starter-test` 或补 import 的新 patch，并重新执行沙箱应用与 `mvn -q test`；最多尝试 2 次 |
| AC-011e | 任务事件流可订阅 | `GET /api/agent/tasks/{id}/stream` 返回 `text/event-stream`，包含 `TASK_SNAPSHOT`、`STEP_SNAPSHOT`、运行中的 `TASK_UPDATED`/`STEP_RECORDED` 和 `STREAM_COMPLETE`；非任务 owner 不能订阅；前端运行任务时显示 stream 状态并以 SSE 事件触发任务详情刷新，断线后保留轮询兜底 |
| AC-011f | Coder 配置可见且脱敏 | `GET /api/settings/coder` 需要鉴权，返回 mode、provider、ready、model、API base URL、key 是否配置、fixture 是否配置、缺失项和支持模式；响应和前端都不展示 API key、fixture response、organization 或 project 原文；控制台展示 `CoderSettingsPanel` 并在默认 `disabled` 模式下显示 recipe/fallback 可用 |
| AC-011g | GitHub 发布配置可见且脱敏 | `GET /api/settings/github` 需要鉴权，返回 provider、ready、publishMode、API base URL、token 是否配置、远程发布是否启用、本地草稿模式和缺失项；响应和前端都不展示 GitHub token 原文；控制台展示 `GitHubSettingsPanel` 并在默认 `LOCAL_DRAFT_ONLY` 模式下显示本地 branch/commit/`DRAFT_READY` 流程可用 |
| AC-011h | Sandbox 运行时配置可见 | `GET /api/settings/sandbox` 需要鉴权，返回 Docker daemon、sandbox image、workspace root、Maven cache、timeout、readiness checks 和缺失项；控制台展示 `SandboxSettingsPanel`，默认环境下显示 Docker sandbox READY、Maven 镜像和 cache/workspace 可用状态 |
| AC-011h2 | 演示就绪总览可见 | 控制台配置区展示 `DemoReadinessPanel`，只从 Coder/GitHub/Sandbox 脱敏配置派生状态，不读取或展示密钥；默认本地环境显示“本地闭环演示：可演示”“真实模型演示：可选增强”“远端 GitHub PR：本地草稿”，并提示 `REPOPILOT_CODER_MODE=openai-compatible`、`REPOPILOT_CODER_API_KEY`/`OPENAI_API_KEY`、`REPOPILOT_CODER_MODEL`、`REPOPILOT_GITHUB_ENABLED=true` 和 `REPOPILOT_GITHUB_TOKEN`/`GITHUB_TOKEN` 等下一步环境变量名 |
| AC-011h3 | 真实 token 演示检查可运行 | `./scripts/real-token-demo-check.sh` 默认只读检查项目文件、本机命令、Docker/Compose、沙箱镜像、Maven cache、后端/前端端口、真实 Coder 和远端 GitHub PR 环境变量，缺少真实 token 时只提示不失败；`--strict` 模式在 Docker、真实 Coder 或远端 PR 缺项时返回非 0；`--start-deps` 可先启动 PostgreSQL/Redis；脚本只展示 key/token 是否配置，不打印 GitHub token、模型 key 或 Authorization header |
| AC-011h4 | 真实 Coder API 演示可运行 | `./scripts/real-coder-demo.sh` 在真实 `OPENAI_COMPATIBLE` Coder 配置 ready 时创建临时用户、本地 demo 项目和一个不会命中本地 recipe 的小任务，运行 Agent 到 `WAITING_HUMAN_APPROVAL`，验证 `generationMode=LLM_CODER_DRAFT`、`generationProvider=OPENAI_COMPATIBLE`、成功的 `generate_patch` model call、`validate_patch_safety`、沙箱 `mvn -q test`、`review_patch` 和预期新增 `.repopilot/real-coder-demo-note.md`；无真实 Coder 配置且后端未运行时脚本返回非 0 并提示缺少环境变量；脚本输出和证据文件不包含模型 key、GitHub token 或 Authorization header，并清理临时业务数据 |
| AC-011h5 | 真实 GitHub PR 演示可运行 | `./scripts/real-github-pr-demo.sh` 必须显式设置 `REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr`、`REPOPILOT_REAL_GITHUB_PR_REPO_URL`、`REPOPILOT_GITHUB_ENABLED=true` 和 GitHub token 才会执行；配置就绪时脚本创建临时用户、指定 GitHub demo 项目和 User count API 任务，验证 recipe patch、沙箱测试、自动审查、人工审批、PR preflight，随后真实 push `repopilot/task-{taskId}` 分支并创建 `OPEN` PR；无确认/仓库/token 时脚本返回非 0 并说明缺项；脚本输出和证据文件不包含 GitHub token、模型 key 或 Authorization header，并清理 RepoPilot 本地临时业务数据；远端 PR 和远端分支由演示者保留或手动清理 |
| AC-011h6 | Agent Worker 契约可验证 | `./scripts/agent-worker-smoke.sh` 可启动或复用 Python Agent Worker，验证 `GET /health` 返回 `status=UP` 和 `service=agent-worker`，验证 `POST /runs/303/start` 返回 `accepted=true`、`status=QUEUED` 和 MVP graph node 清单，并将证据写入 `output/agent-worker-smoke/last-run.json`；当前主执行链路仍由 Spring Boot executor 承担，后续再迁移到 LangGraph worker |
| AC-011h7 | 后端可桥接 Agent Worker 启动 | `REPOPILOT_AGENT_WORKER_ENABLED=false` 默认保持 Spring Boot executor 主链路不变；启用后，后端在 run 执行入口调用 `${REPOPILOT_AGENT_WORKER_URL}/runs/{runId}/start`，请求体包含 `task_id`、`project_id`、`user_request`、`repo_path` 和 `base_branch`，成功时写入 `agent_worker_start` SUCCESS step 和 Worker graph node 证据；调用失败时写入 `agent_worker_start` FAILED step 并继续本地 executor 兜底；`AgentWorkerClientTest` 覆盖 HTTP 契约和非 2xx 错误转译，`AgentTaskServiceRegenerationTest` 覆盖启用桥接后的 step 记录 |
| AC-011h8 | Agent Worker 回写可验证 | 后端提供 `POST /api/internal/agent-worker/runs/{runId}/steps` 回写 step 证据，并提供 `POST /api/internal/agent-worker/runs/{runId}/status` 回写 task/run 状态；两个内部接口都使用 `X-RepoPilot-Worker-Token` 和 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权，不需要用户 JWT；step 请求体使用 `step_name`、`status`、`input`、`output` 和 `error_message`，成功后写入 `agent_step` 并发布 `STEP_RECORDED`；status 请求体支持 `task_status`、`run_status`、`error_message`、`stream_message` 和 `complete_stream`，`task_status` 与 `run_status` 至少传一个，成功后发布 `TASK_UPDATED`，`complete_stream=true` 时发布 `STREAM_COMPLETE`，空状态请求返回 `AGENT_WORKER_STATUS_EMPTY`；错误 token 返回 `AGENT_WORKER_CALLBACK_FORBIDDEN` 且不落库；Python Agent Worker 提供 `BackendApiClient.record_step(...)` 和 `BackendApiClient.update_status(...)`；`AgentWorkerCallbackControllerIntegrationTest` 覆盖真实 MVC/Spring Security/step 落库、状态落库、错误 token 和空状态请求，`./scripts/agent-worker-callback-smoke.sh` 覆盖 Python client 的 step/status 路径、header、JSON 和响应解析，并将证据写入 `output/agent-worker-callback-smoke/last-run.json` |
| AC-011i | 工作台概览指标可见 | `GET /api/dashboard/summary` 需要鉴权，只统计当前用户项目、任务和 PR 记录；返回项目总数/READY/FAILED，任务总数/CREATED/运行中/待审批/DONE/失败/CANCELLED，PR 总数/`DRAFT_READY`/`OPEN`/`FAILED`；控制台展示 `DashboardSummaryPanel`，登录后显示空工作区指标，项目索引完成后更新项目 ready 计数，任务到达人工审批点后更新 waiting approval 计数 |
| AC-011j | Agent 运行表现指标可见 | `GET /api/dashboard/run-metrics` 需要鉴权，只统计当前用户最近 1-30 天 `agent_run`；默认 7 天窗口返回 run 总数、成功/失败/取消/运行中数量、完成 run 数、平均耗时、成功率和每日趋势；控制台展示 `DashboardRunMetricsPanel`，登录后显示 0 runs，首个任务到达人工审批点后显示 runs=1、success rate=100% |
| AC-011k | 跨项目活动流可见 | `GET /api/dashboard/activity` 需要鉴权，只统计当前用户任务下最近 `agent_step`，按完成/开始时间倒序返回，支持 `limit` 且不返回 step input/output JSON；控制台展示 `DashboardActivityPanel`，登录后显示无活动，首个任务到达人工审批点后显示 `waiting_human_approval` 活动 |
| AC-011l | 任务筛选与搜索可用 | `GET /api/agent/tasks` 支持 `projectId`、`status`、`taskType` 和 `query` 查询参数，只返回当前用户任务；`query` 对标题/描述大小写不敏感搜索；控制台展示任务筛选表单，支持 Apply filters 和 Reset，运行中轮询/SSE 刷新继续沿用当前筛选条件 |
| AC-011m | 项目筛选与搜索可用 | `GET /api/projects` 支持 `status` 和 `query` 查询参数，只返回当前用户项目；`query` 对 `repoFullName`/`repoUrl` 大小写不敏感搜索；控制台展示项目筛选表单，支持 Apply filters 和 Reset，项目列表筛选不影响任务创建、任务筛选和任务详情所需的完整项目上下文 |
| AC-011n | 项目视图 URL 可恢复 | 控制台 URL 支持 `projectStatus`、`projectQuery` 和 `projectId` 参数；刷新页面后项目筛选表单恢复，后端重新请求对应 `GET /api/projects?status=...&query=...`，Repository insight 项目选择器恢复到 `projectId`；Reset 清空项目筛选参数但保留当前可见项目选择 |
| AC-011o | 任务视图 URL 可恢复 | 控制台 URL 支持 `taskProjectId`、`taskStatus`、`taskType`、`taskQuery` 和 `taskId` 参数；刷新页面后任务筛选表单恢复，后端重新请求对应 `GET /api/agent/tasks?...`，任务详情恢复到 `taskId`；Reset 清空任务筛选参数但保留当前任务详情 |
| AC-011p | 任务视图链接可复制 | 任务筛选表单提供 `Copy task view link`；点击后通过剪贴板写入当前控制台 URL，包含当前任务筛选参数和 `taskId`，并显示可访问的复制状态提示 |
| AC-011q | 项目视图链接可复制 | 项目筛选表单提供 `Copy project view link`；点击后通过剪贴板写入当前控制台 URL，包含当前项目筛选参数和 `projectId`，并显示可访问的复制状态提示 |
| AC-011r | Agent 运行指标窗口可切换 | `DashboardRunMetricsPanel` 提供 7/14/30 天窗口选择；切换到 14 天时前端请求 `GET /api/dashboard/run-metrics?days=14` 并更新面板为 `Last 14 days`；URL 写入 `runMetricsDays=14`，刷新后选择器和指标窗口恢复 |
| AC-011s | 活动流数量窗口可切换 | `DashboardActivityPanel` 提供 10/25/50 条数量选择；切换到 25 条时前端请求 `GET /api/dashboard/activity?limit=25` 并更新面板为 `0 of latest 25 events` 或对应活动数量；URL 写入 `activityLimit=25`，刷新后选择器和活动窗口恢复 |
| AC-011t | 工作台概览链接可复制 | `DashboardSummaryPanel` 提供 `Copy overview link`；点击后通过剪贴板写入当前控制台 URL，包含当前 `runMetricsDays`、`activityLimit` 和 `#overview` 锚点，并显示可访问的复制状态提示 |
| AC-012 | 全链路可演示 | `./scripts/agent-worker-smoke.sh` 可先验证 Python Agent Worker 契约；`./scripts/agent-worker-callback-smoke.sh` 可验证 Python Agent Worker 到后端 step/status 回写 client 契约；`./scripts/real-token-demo-check.sh` 可完成演示环境体检；有真实模型 token 时，`./scripts/real-coder-demo.sh` 可验证真实 `OPENAI_COMPATIBLE` Coder 到沙箱测试和人工审批暂停点的 API 级链路；有 GitHub token 和可丢弃 demo 仓库时，`./scripts/real-github-pr-demo.sh` 可验证真实远端分支 push 和 GitHub PR 创建；`./scripts/browser-smoke.sh` 从登录后的工作台概览、Agent run performance 7/14/30 天窗口切换与 URL 恢复、Recent task activity 10/25/50 条数量切换与 URL 恢复、overview 链接复制、演示就绪总览、Coder、GitHub 发布与 Sandbox 配置脱敏状态开始，继续完成仓库接入、项目搜索/状态筛选、项目视图 URL 恢复、项目视图链接复制、Controller API 风险视图、Controller API Markdown 文档复制和 `.md` 下载、代码检索、任务搜索/状态筛选、任务视图 URL 恢复、任务视图链接复制、Agent evidence、run report 复制和 `.md` 下载、运行报告快照保存/复制/下载、真实分页 patch、`changedFiles` 摘要、`validate_patch_safety` 预检、自动风险审查、沙箱测试、Regenerate 新版本校验、PR preflight blocker/ready 状态、人工审批和本地 `DRAFT_READY` PR 准备记录；随后创建 User id 参数校验任务并验证真实 guard patch、测试覆盖和沙箱测试通过；再创建 User count API 任务并验证 `SPRING_USER_COUNT_RECIPE`、真实 count patch、`changedFiles` 和沙箱测试通过；最后创建 User create API 任务并验证 `SPRING_USER_CREATE_RECIPE`、真实 create patch、DTO 文件、`changedFiles` 和沙箱测试通过 |

## 3. Demo 验收脚本

1. 运行 `./scripts/agent-worker-smoke.sh` 验证 Python Agent Worker 契约。
2. 运行 `./scripts/agent-worker-callback-smoke.sh` 验证 Python Agent Worker 回写 client 契约。
3. 运行 `./scripts/real-token-demo-check.sh` 检查本地闭环、真实模型和远端 GitHub PR 演示前置项；正式真实 token 演示前使用 `--strict`。
4. 有真实模型 token 时，运行 `./scripts/real-coder-demo.sh` 展示真实 `OPENAI_COMPATIBLE` Coder 从模型调用到沙箱测试和人工审批暂停点的 API 级端到端链路。
5. 有 GitHub token 和可丢弃 demo 仓库时，运行 `./scripts/real-github-pr-demo.sh` 展示真实远端分支 push 和 GitHub PR 创建。
6. 启动 Docker Compose。
7. 注册并登录。
8. 查看工作台概览、Agent run performance、Recent task activity、Coder、GitHub 发布与 Sandbox 配置状态，确认空工作区计数、run 计数/成功率、空活动流、mode、provider、ready、model/key/token、Docker、Maven cache 和 workspace 配置状态可见且无密钥明文。
9. 添加 Spring Boot 示例仓库。
10. Clone 成功后，使用项目搜索和 `READY` 状态筛选仓库行，确认 URL 写入 `projectStatus`、`projectQuery` 和 `projectId`，刷新页面后恢复筛选和 Repository insight 项目选择，复制当前项目视图链接并确认剪贴板 URL 包含项目筛选和 `projectId`，然后重置项目筛选。
11. 触发索引。
12. 创建任务：“给 User 模块新增分页查询接口”。
13. 使用任务搜索筛选分页任务，确认 URL 写入 `taskQuery` 和 `taskId`，刷新页面后恢复任务筛选和任务详情；任务到审批点后按 `WAITING_HUMAN_APPROVAL` 状态筛选，再次刷新确认 `taskStatus`、`taskQuery` 和任务详情恢复，复制当前任务视图链接并确认剪贴板 URL 包含任务筛选和 `taskId`，然后重置任务筛选。
14. 观察 Agent 步骤、Agent evidence 和日志流，复制、下载并保存当前 run report Markdown，再从运行报告快照复制和下载历史报告。
15. 查看相关代码检索结果。
16. 查看新增 `GET /api/users/page`、Service/Mapper 分页逻辑和 `UserServiceTest` 的 diff。
17. 查看 Maven 测试日志。
18. 查看 PR preflight，确认未审批 blocker。
19. 点击 Approve。
20. 查看 PR preflight，确认本地 branch/commit 可准备且远程 GitHub 状态可解释。
21. 准备 PR，展示本地 target branch、commit、标题和描述。
22. 再创建任务：“修复 User id 参数校验 bug”，查看 guard、`UserServiceTest` 和 Maven 测试结果。
23. 再创建任务：“新增 User count API”，查看 `GET /api/users/count`、`countUsers`、`countAll`、`UserServiceTest` 和 Maven 测试结果。
24. 再创建任务：“新增 User create API”，查看 `POST /api/users`、`CreateUserRequest`、`createUser`、`save`、`UserServiceTest` 和 Maven 测试结果。
25. 在启用 GitHub 发布的环境中创建 GitHub PR，并打开 PR 链接展示标题、描述和修改文件。

## 4. 风险验收

| 风险 | 必须满足 |
| --- | --- |
| 密钥泄露 | 日志中不出现 GitHub token、模型 key、Authorization header |
| 越权文件访问 | 工具不能读取项目工作区以外路径 |
| 未审批创建 PR | 后端状态校验阻止该行为 |
| 测试污染宿主 | Maven 执行发生在沙箱或隔离 run 工作区 |
| 状态不可追踪 | 每个任务有 run、step、tool call、test run |
