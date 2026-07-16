# RepoPilot 产品需求文档

## 1. 项目定位

RepoPilot 是一个面向 Java/Spring Boot 仓库的 AI 软件工程 Agent 平台。系统接入 GitHub 仓库后，能够理解项目结构，分析自然语言任务或 Issue，检索相关代码，生成统一 diff，在 Docker 沙箱中编译测试，并在用户审批后创建 Pull Request。

项目不是普通聊天机器人，而是一个有状态、有审计、有人工审批的后端工程平台。

## 2. 目标用户

| 用户类型 | 典型诉求 |
| --- | --- |
| Java 后端开发者 | 快速定位 Spring Boot 项目中的 Controller、Service、Mapper、Entity，并生成可审查修改 |
| 项目维护者 | 根据 Issue 自动生成修复计划、测试结果和 PR，降低重复维护成本 |
| 技术团队负责人 | 观察 Agent 每一步工具调用、测试结果和审批记录，保证改动可控 |
| 面试官或项目评审者 | 看到 AI Agent 与 Java 后端工程能力的完整闭环 |

## 3. 核心业务目标

| 编号 | 目标 | 衡量方式 |
| --- | --- | --- |
| PRD-G-001 | 跑通 Java 仓库维护闭环 | 从仓库接入到 PR 创建的演示链路可执行 |
| PRD-G-002 | 体现 Java 后端工程能力 | 后端包含鉴权、任务流、数据库、异步执行、日志审计和外部 API 集成 |
| PRD-G-003 | 体现 Agent 工程能力 | 支持计划、检索、生成、测试、修复、审批的多阶段工作流 |
| PRD-G-004 | 体现工程安全性 | 默认在沙箱执行，生成 diff 后等待人工审批，不直接污染用户仓库 |

## 4. 核心场景

### 4.1 功能开发场景

用户选择一个已接入的 Spring Boot 仓库，输入“给 User 模块新增分页查询接口”。RepoPilot 检索相关 Controller、Service、Mapper、Entity，生成修改计划和 diff，在沙箱中运行 Maven 测试。测试通过后，用户审批并创建 PR。

### 4.2 Bug 修复场景

用户输入 Issue 描述或粘贴报错日志。RepoPilot 定位相关代码，生成修复计划，修改后运行测试。如果测试失败，系统把失败日志交给 RepairAgent 进行有限次数修复。

### 4.3 PR 风险审查场景

用户上传或选择一个 diff。RepoPilot 使用 ReviewAgent 检查鉴权、参数校验、SQL 风险、破坏性变更和测试缺口，并输出风险报告。当前 MVP 已提供规则化 patch 风险审查，后续可替换或增强为 LLM ReviewAgent。

## 5. 功能需求

| 编号 | 模块 | 需求描述 | 优先级 |
| --- | --- | --- | --- |
| PRD-F-001 | 用户与鉴权 | 支持注册、登录、JWT 鉴权和当前用户信息查询 | P0 |
| PRD-F-002 | 项目接入 | 用户可输入 GitHub 仓库地址和访问 token，系统 clone 仓库到受控工作区 | P0 |
| PRD-F-003 | 仓库扫描 | 系统识别 Java/Spring Boot 项目结构，包括 Controller、Service、Mapper、Entity、配置文件和测试目录 | P0 |
| PRD-F-004 | AST 解析 | 系统解析 Java 类、方法、字段、注解和包路径，生成结构化符号索引 | P0 |
| PRD-F-005 | 代码切片 | 系统按类、方法和文件上下文生成代码 chunk，用于检索和 Agent 上下文构建 | P0 |
| PRD-F-006 | 向量检索 | 系统支持根据自然语言任务检索相关代码 chunk 和符号 | P0 |
| PRD-F-007 | Agent 任务 | 用户可创建 FEATURE、BUGFIX、REVIEW、DOC 类型的 Agent 任务；同一项目写入型任务必须互斥，避免多个 run 同时修改同一工作区 | P0 |
| PRD-F-008 | 任务规划 | PlannerAgent 输出步骤化修改计划，计划必须可在 UI 中查看 | P0 |
| PRD-F-009 | 上下文检索 | RetrieverAgent 输出相关文件、类、方法和理由 | P0 |
| PRD-F-010 | diff 生成 | CoderAgent 生成 unified diff，默认不直接覆盖原文件；当前 MVP 已支持 Spring Coder recipe catalog，包含分页接口、User id 参数校验、User count API 和 User create API 四条 recipe，并持久化 `generationMode`；未命中时默认保留 `SAFE_PLANNING_FALLBACK`，生成带检索候选文件、符号、行号、编辑顺序和验证门槛的安全 Coder plan diff；可配置 Coder model client 已接入，`fixture` 模式会先解析 raw response，`openai-compatible` 模式会调用 Chat Completions 兼容接口生成 raw response；两者都仅接受 raw unified diff 或单个 diff 代码块并生成 `LLM_CODER_DRAFT` | P0 |
| PRD-F-011 | 沙箱执行 | 系统在 Docker 沙箱或隔离工作区中应用 diff 并运行 `mvn test`；应用前必须执行 diff 安全预检，拒绝路径穿越、绝对路径、保留目录和二进制 patch | P0 |
| PRD-F-012 | 测试日志分析 | 测试失败时，系统总结失败原因、相关文件和建议修复方向 | P0 |
| PRD-F-013 | 自动修复 | RepairAgent 可根据失败日志尝试修复，MVP 限制最多 2 次；当前已支持测试依赖缺失时自动补充 `spring-boot-starter-test`，以及常见 Java 标准库缺 import 编译失败时生成补 import 的第二版 patch 并重跑沙箱测试 | P1 |
| PRD-F-014 | 人工审批 | diff 必须经用户 Approve 后才能创建 PR | P0 |
| PRD-F-015 | PR 创建 | 系统调用 GitHub API 创建分支、提交和 Pull Request | P0 |
| PRD-F-016 | 实时日志 | 用户能查看 Agent 步骤、工具调用、耗时、状态和错误信息；当前已提供 task/run/step 快照加运行中持续推送的 SSE，前端任务详情会订阅 SSE 并保留轮询兜底，并通过 Agent evidence 面板展示计划、检索、patch、沙箱测试、自动审查、人工审批 checkpoint 和后端 run report 诊断段落；`GET /api/agent/tasks/{id}/run-report` 可生成当前 run 的结构化证据报告和 Markdown，并在存在 model/tool retry audit 时汇总 `Worker 重试恢复证据`；前端支持复制或下载该报告，并可保存运行报告快照，历史快照可复制或下载，便于 rerun 后保留旧报告 | P0 |
| PRD-F-017 | 工具审计 | 系统记录每次工具调用的输入、输出摘要、状态和耗时 | P0 |
| PRD-F-018 | API 文档生成 | 扫描 Controller 自动生成接口文档；`GET /api/projects/{id}/controller-apis/docs` 可按风险筛选生成 Markdown，当前项目洞察支持复制、下载或保存该文档快照，包含路由、参数、request/response、调用链、风险提示和源码位置；快照可在项目内查看最近历史，并可从历史快照直接复制、下载、删除或批量清空 Markdown，便于跨索引周期审计、复用和整理 | P1 |
| PRD-F-019 | 调用链分析 | 展示 Controller 到 Service、Mapper、DB 的调用链 | P1 |
| PRD-F-020 | 权限风险检查 | 检查新增或修改接口是否缺少鉴权、参数校验和敏感数据保护 | P1 |
| PRD-F-021 | Coder 配置可见性 | 控制台展示当前 Coder mode、provider、model、API endpoint、key 是否配置、fixture 是否配置和缺失配置项，所有密钥仍只通过环境变量注入且不在 API/UI 中暴露 | P0 |
| PRD-F-022 | GitHub 发布配置可见性 | 控制台展示当前 GitHub PR 发布模式、provider、API endpoint、token 是否配置、远程发布是否启用和缺失配置项；未启用远程发布时明确显示本地 `DRAFT_READY` 模式可用，token 不在 API/UI 中暴露 | P0 |
| PRD-F-023 | PR 发布前置检查可见性 | 任务详情展示 PR 准备前置检查，明确任务状态、patch 审批、沙箱测试、本地草稿准备、远程 GitHub 发布和 token 配置是否满足；不满足时展示可操作 blocker | P0 |
| PRD-F-024 | Sandbox 运行时配置可见性 | 控制台展示 Docker daemon、sandbox image、Maven cache、workspace root 和超时配置的 readiness，帮助用户在长任务前确认测试执行前置条件 | P0 |
| PRD-F-025 | 工作台概览指标 | 控制台展示当前用户项目、任务运行态、待审批、失败、完成和 PR 草稿/打开计数，帮助用户不用逐个打开任务即可判断当前 RepoPilot 工作区状态 | P0 |
| PRD-F-026 | Agent 运行表现指标 | 控制台展示当前用户最近 Agent run 总数、成功率、平均耗时、运行中数量和每日趋势，帮助用户区分当前状态与近期执行表现 | P0 |
| PRD-F-027 | 跨项目活动流 | 控制台展示当前用户最近 Agent step 活动，包含项目、任务、step、状态和发生时间，帮助用户不用逐个打开任务也能理解最近系统推进 | P0 |
| PRD-F-028 | 任务筛选与搜索 | 任务列表支持按项目、状态、任务类型和标题/描述关键词筛选，帮助用户在多项目、多任务工作区里快速定位目标任务 | P0 |
| PRD-F-029 | 项目筛选与搜索 | 项目列表支持按状态和仓库名称/URL 关键词筛选，帮助用户在多仓库工作区里快速定位要 clone、索引或查看洞察的项目 | P0 |
| PRD-F-030 | 项目视图可恢复 | 项目筛选条件和当前洞察项目可通过控制台 URL 恢复，帮助用户刷新页面或分享链接后回到同一个仓库视图 | P0 |
| PRD-F-031 | 任务视图可恢复 | 任务筛选条件和当前任务可通过控制台 URL 恢复，帮助用户刷新页面或分享链接后回到同一个任务队列和任务详情 | P0 |
| PRD-F-032 | 任务视图链接分享 | 控制台支持一键复制当前任务筛选和任务详情链接，帮助用户把可恢复的任务队列视图交给评审者或协作者 | P0 |
| PRD-F-033 | 项目视图链接分享 | 控制台支持一键复制当前项目筛选和 Repository insight 项目链接，帮助用户把可恢复的仓库视图交给评审者或协作者 | P0 |
| PRD-F-034 | Agent 运行指标窗口切换 | 控制台支持在最近 7/14/30 天之间切换 Agent run performance 指标窗口，并通过 URL 恢复当前窗口，帮助用户按不同时间尺度观察吞吐、成功率和耗时趋势 | P0 |
| PRD-F-035 | 活动流数量窗口切换 | 控制台支持在最近 10/25/50 条活动之间切换 Recent task activity，并通过 URL 恢复当前数量窗口，帮助用户在排查任务推进时查看更多跨项目步骤事件 | P0 |
| PRD-F-036 | 工作台概览链接分享 | 控制台支持一键复制当前 overview 链接，包含 Agent run 指标窗口、活动流数量窗口和 `#overview` 锚点，帮助协作者直接打开同一个 Dashboard 观察状态 | P0 |

## 6. 非功能需求

| 编号 | 类别 | 需求描述 | 优先级 |
| --- | --- | --- | --- |
| PRD-NF-001 | 安全 | GitHub token 和模型 key 必须加密或脱敏存储，日志不得输出明文密钥 | P0 |
| PRD-NF-002 | 隔离 | 代码编译、测试和补丁应用必须在项目工作区或 Docker 沙箱中进行 | P0 |
| PRD-NF-003 | 可追踪 | 每个 Agent 任务必须保留状态流转、模型调用摘要、工具调用日志和测试结果 | P0 |
| PRD-NF-004 | 可恢复 | 任务失败后状态可查询，失败原因可读，工作区可清理或重新执行 | P0 |
| PRD-NF-005 | 性能 | MVP 下单仓库 2 万行以内的索引任务应在可接受时间内完成，目标小于 5 分钟 | P1 |
| PRD-NF-006 | 可扩展 | Agent Worker、MCP Tool Server、主后端之间通过清晰 API 解耦 | P1 |
| PRD-NF-007 | 可部署 | 本地开发环境通过 Docker Compose 一键启动 PostgreSQL、Redis、后端和 Worker | P0 |
| PRD-NF-008 | 可演示 | 系统必须提供演示仓库和标准 Demo 脚本 | P0 |

## 7. 产品边界

MVP 阶段只重点支持 Java/Spring Boot + Maven 项目。Gradle、多语言仓库、复杂 monorepo、自动合并 PR、生产级权限模型和企业级多租户暂缓。

## 8. 成功标准

1. 用户能接入一个公开或私有 GitHub Java 仓库。
2. 系统能扫描并展示仓库结构和核心 Java 符号。
3. 用户能创建自然语言 Agent 任务并看到执行过程。
4. 系统能生成 diff，并在沙箱中运行 Maven 测试。
5. 用户审批后，系统能创建 GitHub Pull Request。
6. 全链路日志、测试结果和 PR 链接可在任务详情中追溯。
