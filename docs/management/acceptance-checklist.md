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
| AC-006b | Agent 运行证据可读 | `GET /api/agent/tasks/{id}/run-report` 需要鉴权，只允许任务 owner 读取 current run 证据，返回 planner、retrieval、patch、patch safety、sandbox tests、automated review 和 human approval checkpoint sections 以及 Markdown；`POST /api/agent/tasks/{id}/run-report/snapshots` 可保存当前报告快照，`GET /api/agent/tasks/{id}/run-report/snapshots` 可列出最近快照摘要，`GET /api/agent/tasks/{id}/run-report/snapshots/{snapshotId}` 可读取历史快照 Markdown，所有快照接口只允许任务 owner 访问；前端任务详情展示 `Agent evidence`，从当前 run 的 step JSON 中解析并展示 `Planner task plan`、`Retrieved code context`、`Generated patch artifact`、`Patch safety gate`、`Sandbox test result`、`Automated patch review` 和 `Human approval checkpoint`，并支持复制/下载当前报告、保存运行报告快照、复制/下载历史快照；原始 model/tool audit 仍可查看 |
| AC-007 | Agent 可以生成 diff | 默认分页 demo 任务生成真实 unified diff，包含 `GET /api/users/page`、Service/Mapper 分页逻辑、`spring-boot-starter-test` 和 `UserServiceTest`，patch `generationMode=SPRING_USER_PAGINATION_RECIPE`；User id 参数校验 demo 任务生成真实 guard 与单元测试 diff，patch `generationMode=SPRING_USER_ID_VALIDATION_RECIPE`；User count demo 任务生成真实 `GET /api/users/count`、Service/Mapper count 逻辑和单元测试，patch `generationMode=SPRING_USER_COUNT_RECIPE`；User create demo 任务生成真实 `POST /api/users`、`CreateUserRequest`、Service/Mapper create 逻辑和单元测试，patch `generationMode=SPRING_USER_CREATE_RECIPE`；LLM Coder draft 入口解析 raw response 后生成 `generationMode=LLM_CODER_DRAFT` patch，且只接受 raw unified diff 或单个 diff 代码块；`fixture` 模式已有生产状态机级测试，验证 raw diff 会经过 parser、安全预检、Docker 沙箱 `mvn test`、review 和人工审批暂停点；`openai-compatible` Coder 模式会调用 Chat Completions 兼容接口，带上检索上下文和 diff-only prompt，再复用同一 parser、安全预检、沙箱测试和 review 链路；patch API 返回 `changedFiles` 文件级变更摘要和增删行数；未知任务回退为 `SAFE_PLANNING_FALLBACK` retrieval-grounded Coder plan diff，包含检索候选文件、符号、行号、编辑顺序和验证门槛；Regenerate 会创建新的 Agent run 和 patch 版本 |
| AC-008 | 系统可以在沙箱运行测试 | `test_run` 记录 exit code 和日志；沙箱应用前执行 `validate_patch_safety`，拒绝路径穿越、绝对路径、保留目录和二进制 patch；分页 demo patch、User id 参数校验 demo patch、User count demo patch 与 User create demo patch 都在沙箱中执行 `mvn -q test` 通过 |
| AC-009 | 未审批不能创建 PR | API 返回 `PATCH_NOT_APPROVED` |
| AC-010 | 审批后可以准备 PR | 未开启 GitHub 发布时生成 `pull_request_record.status=DRAFT_READY`、target branch 和 commit，并将 task 标为 `DONE` 释放项目写入槽；开启 GitHub 发布并提供 token 后生成 `OPEN` 记录且 `pull_request_record.url` 可打开 |
| AC-010b | PR 发布前置检查可见 | `GET /api/tasks/{id}/pull-request/preflight` 需要鉴权，返回 task、patch、test、local draft、remote GitHub 的 `PASS`/`PENDING`/`BLOCKED`/`WARN` 检查项和 blockers；前端 PR 面板展示 preflight，审批前显示需要 Approve 的 blocker，审批且测试通过后显示本地 branch/commit 可准备，准备完成后显示已有 `DRAFT_READY` 记录 |
| AC-011 | 工具调用可审计 | `tool_call_log` 可按 run 查询，`GET /api/agent/runs/{runId}/tool-calls` 返回脱敏输入、输出摘要、状态和耗时 |
| AC-011b | 模型调用可追踪 | `model_call_log` 可按 run 查询，`GET /api/agent/runs/{runId}/model-calls` 返回脱敏 prompt、response 摘要、模型名、token 和耗时 |
| AC-011c | Patch 风险审查可见 | `review_patch` step 输出 `riskLevel`、`summary` 和 findings；前端 Patch 面板展示 `Automated review`，包含新增接口缺鉴权、分页边界和测试覆盖提示 |
| AC-011d | RepairAgent 修复循环可追踪 | Maven 测试因缺少测试依赖失败时，任务进入 `REPAIRING`，`repair_patch` step/model call 生成补充 `spring-boot-starter-test` 的新 patch，并重新执行沙箱应用与 `mvn -q test`；最多尝试 2 次 |
| AC-011e | 任务事件流可订阅 | `GET /api/agent/tasks/{id}/stream` 返回 `text/event-stream`，包含 `TASK_SNAPSHOT`、`STEP_SNAPSHOT`、运行中的 `TASK_UPDATED`/`STEP_RECORDED` 和 `STREAM_COMPLETE`；非任务 owner 不能订阅；前端运行任务时显示 stream 状态并以 SSE 事件触发任务详情刷新，断线后保留轮询兜底 |
| AC-011f | Coder 配置可见且脱敏 | `GET /api/settings/coder` 需要鉴权，返回 mode、provider、ready、model、API base URL、key 是否配置、fixture 是否配置、缺失项和支持模式；响应和前端都不展示 API key、fixture response、organization 或 project 原文；控制台展示 `CoderSettingsPanel` 并在默认 `disabled` 模式下显示 recipe/fallback 可用 |
| AC-011g | GitHub 发布配置可见且脱敏 | `GET /api/settings/github` 需要鉴权，返回 provider、ready、publishMode、API base URL、token 是否配置、远程发布是否启用、本地草稿模式和缺失项；响应和前端都不展示 GitHub token 原文；控制台展示 `GitHubSettingsPanel` 并在默认 `LOCAL_DRAFT_ONLY` 模式下显示本地 branch/commit/`DRAFT_READY` 流程可用 |
| AC-011h | Sandbox 运行时配置可见 | `GET /api/settings/sandbox` 需要鉴权，返回 Docker daemon、sandbox image、workspace root、Maven cache、timeout、readiness checks 和缺失项；控制台展示 `SandboxSettingsPanel`，默认环境下显示 Docker sandbox READY、Maven 镜像和 cache/workspace 可用状态 |
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
| AC-012 | 全链路可演示 | `./scripts/browser-smoke.sh` 从登录后的工作台概览、Agent run performance 7/14/30 天窗口切换与 URL 恢复、Recent task activity 10/25/50 条数量切换与 URL 恢复、overview 链接复制、Coder、GitHub 发布与 Sandbox 配置脱敏状态开始，继续完成仓库接入、项目搜索/状态筛选、项目视图 URL 恢复、项目视图链接复制、Controller API 风险视图、Controller API Markdown 文档复制和 `.md` 下载、代码检索、任务搜索/状态筛选、任务视图 URL 恢复、任务视图链接复制、Agent evidence、run report 复制和 `.md` 下载、运行报告快照保存/复制/下载、真实分页 patch、`changedFiles` 摘要、`validate_patch_safety` 预检、自动风险审查、沙箱测试、Regenerate 新版本校验、PR preflight blocker/ready 状态、人工审批和本地 `DRAFT_READY` PR 准备记录；随后创建 User id 参数校验任务并验证真实 guard patch、测试覆盖和沙箱测试通过；再创建 User count API 任务并验证 `SPRING_USER_COUNT_RECIPE`、真实 count patch、`changedFiles` 和沙箱测试通过；最后创建 User create API 任务并验证 `SPRING_USER_CREATE_RECIPE`、真实 create patch、DTO 文件、`changedFiles` 和沙箱测试通过 |

## 3. Demo 验收脚本

1. 启动 Docker Compose。
2. 注册并登录。
3. 查看工作台概览、Agent run performance、Recent task activity、Coder、GitHub 发布与 Sandbox 配置状态，确认空工作区计数、run 计数/成功率、空活动流、mode、provider、ready、model/key/token、Docker、Maven cache 和 workspace 配置状态可见且无密钥明文。
4. 添加 Spring Boot 示例仓库。
5. Clone 成功后，使用项目搜索和 `READY` 状态筛选仓库行，确认 URL 写入 `projectStatus`、`projectQuery` 和 `projectId`，刷新页面后恢复筛选和 Repository insight 项目选择，复制当前项目视图链接并确认剪贴板 URL 包含项目筛选和 `projectId`，然后重置项目筛选。
6. 触发索引。
7. 创建任务：“给 User 模块新增分页查询接口”。
8. 使用任务搜索筛选分页任务，确认 URL 写入 `taskQuery` 和 `taskId`，刷新页面后恢复任务筛选和任务详情；任务到审批点后按 `WAITING_HUMAN_APPROVAL` 状态筛选，再次刷新确认 `taskStatus`、`taskQuery` 和任务详情恢复，复制当前任务视图链接并确认剪贴板 URL 包含任务筛选和 `taskId`，然后重置任务筛选。
9. 观察 Agent 步骤、Agent evidence 和日志流，复制、下载并保存当前 run report Markdown，再从运行报告快照复制和下载历史报告。
10. 查看相关代码检索结果。
11. 查看新增 `GET /api/users/page`、Service/Mapper 分页逻辑和 `UserServiceTest` 的 diff。
12. 查看 Maven 测试日志。
13. 查看 PR preflight，确认未审批 blocker。
14. 点击 Approve。
15. 查看 PR preflight，确认本地 branch/commit 可准备且远程 GitHub 状态可解释。
16. 准备 PR，展示本地 target branch、commit、标题和描述。
17. 再创建任务：“修复 User id 参数校验 bug”，查看 guard、`UserServiceTest` 和 Maven 测试结果。
18. 再创建任务：“新增 User count API”，查看 `GET /api/users/count`、`countUsers`、`countAll`、`UserServiceTest` 和 Maven 测试结果。
19. 再创建任务：“新增 User create API”，查看 `POST /api/users`、`CreateUserRequest`、`createUser`、`save`、`UserServiceTest` 和 Maven 测试结果。
20. 在启用 GitHub 发布的环境中创建 GitHub PR，并打开 PR 链接展示标题、描述和修改文件。

## 4. 风险验收

| 风险 | 必须满足 |
| --- | --- |
| 密钥泄露 | 日志中不出现 GitHub token、模型 key、Authorization header |
| 越权文件访问 | 工具不能读取项目工作区以外路径 |
| 未审批创建 PR | 后端状态校验阻止该行为 |
| 测试污染宿主 | Maven 执行发生在沙箱或隔离 run 工作区 |
| 状态不可追踪 | 每个任务有 run、step、tool call、test run |
