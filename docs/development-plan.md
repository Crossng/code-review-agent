下面给你一版**完整项目策划表**。我建议你就按这个方向做，不要再分散选题。

# 项目总方向

## 项目名称

**RepoPilot：面向 Java/Spring Boot 项目的 AI 软件工程 Agent 平台**

英文简历名：

**RepoPilot: Agentic Backend Maintenance Platform for Java/Spring Boot Repositories**

---

## 一句话介绍

> RepoPilot 是一个面向 Java 后端仓库的 AI Agent 平台，能够接入 GitHub 仓库，理解 Spring Boot 项目结构，分析 Issue，检索相关代码，生成代码补丁，在 Docker 沙箱中编译测试，并在人工审批后创建 Pull Request。

---

# 1. 为什么这个选题最适合你

这个项目比普通 RAG 问答、AI 助手、Dify 二开更适合你，因为它同时覆盖：

| 目标             | 这个项目能体现什么                                              |
| -------------- | ------------------------------------------------------ |
| 投 **Java 后端**  | Spring Boot、权限、任务流、数据库、异步任务、GitHub API、Docker、日志、状态机   |
| 投 **AI Agent** | 多 Agent 协作、工具调用、MCP、代码库 RAG、计划-执行-反思、Human-in-the-loop |
| 投 **AI 工程化**   | 沙箱执行、可观测性、工具调用审计、失败重试、PR 自动化                           |
| 结合你背景          | 你本来就是 CS，本科项目经验偏工程和 AI，这个项目能把“AI + 后端 + 工程落地”连起来       |

技术趋势上，这个方向是有依据的。LangGraph 官方强调的是 long-running、stateful agent orchestration，并支持 durable execution、streaming、human-in-the-loop 等能力；这正好对应我们要做的长任务 Agent 工作流。([Docs by LangChain][1]) MCP 的官方定位是让 AI 应用与外部数据源和工具建立标准化连接，适合作为 Agent 工具层。([Anthropic][2]) Spring AI MCP Server Boot Starter 已经支持把 Spring Boot 应用配置成 MCP Server，暴露工具能力给 AI 应用调用。([Home][3]) OpenHands 这类软件工程 Agent 平台也明确强调 Docker sandbox，因为代码执行必须隔离宿主环境。([OpenHands Docs][4])

---

# 2. 项目方向总表

| 维度      | 规划                                                       |
| ------- | -------------------------------------------------------- |
| 项目类型    | AI Agent + Java 后端平台                                     |
| 核心场景    | 自动维护 Java/Spring Boot 仓库                                 |
| 目标用户    | Java 后端开发者、项目维护者、技术团队                                    |
| 核心输入    | GitHub 仓库地址、Issue 描述、自然语言任务                              |
| 核心输出    | 代码分析报告、修改计划、代码 diff、测试日志、PR                              |
| 核心闭环    | Issue → 代码检索 → 任务规划 → 生成补丁 → Docker 测试 → 人工审批 → 创建 PR    |
| 技术亮点    | MCP、Agent 工作流、代码库 RAG、AST 解析、Docker 沙箱、GitHub API、工具调用追踪 |
| 简历定位    | Agentic Software Engineering Platform                    |
| 难度等级    | 中高，但可分阶段完成                                               |
| 最低可展示版本 | 4–6 周                                                    |
| 完整简历版本  | 8–12 周                                                   |
| 最终展示方式  | GitHub 开源仓库 + 在线 Demo + 项目文档 + 演示视频                      |

---

# 3. 最终项目形态

你不要做成一个简单 Chatbot，而是做成一个**后端工程平台**。

## 用户使用流程

```text
1. 用户登录系统
2. 添加 GitHub 仓库
3. 系统 clone 仓库并分析项目结构
4. 用户输入任务，例如：
   “帮我给 User 模块新增分页查询接口”
5. Agent 检索相关 Controller / Service / Mapper / Entity
6. Agent 输出修改计划
7. Agent 生成代码 diff
8. 系统在 Docker 沙箱里运行 mvn test
9. 测试失败则 Agent 根据日志自动修复
10. 测试通过后等待用户审批
11. 用户点击 Approve
12. 系统创建 GitHub Pull Request
```

---

# 4. 项目功能规划表

## 4.1 MVP 必做功能

| 功能模块       | 功能描述                                | 是否必须 | 简历价值              |
| ---------- | ----------------------------------- | ---: | ----------------- |
| 用户登录       | 注册、登录、JWT 鉴权                        |   必须 | Java 后端基础能力       |
| 项目接入       | 输入 GitHub repo URL，clone 到本地工作区     |   必须 | Git/GitHub 工程能力   |
| 仓库扫描       | 扫描 Java/Spring Boot 项目目录结构          |   必须 | 代码分析基础            |
| AST 解析     | 解析 Controller、Service、Entity、Mapper |   必须 | 区分普通 RAG 项目       |
| 代码索引       | 对代码 chunk 做向量索引                     |   必须 | RAG 能力            |
| Agent 任务创建 | 用户输入自然语言任务                          |   必须 | Agent 入口          |
| 任务规划       | Agent 输出修改步骤                        |   必须 | Agent 编排能力        |
| 代码检索       | 根据任务检索相关文件和方法                       |   必须 | 代码库 RAG           |
| 生成 diff    | 生成 unified diff，不直接覆盖文件             |   必须 | 工程安全性             |
| Docker 测试  | 在容器中执行 mvn test                     |   必须 | 高级工程亮点            |
| 测试日志分析     | 失败时总结错误原因                           |   必须 | Agent 反思能力        |
| 人工审批       | 用户确认后才应用改动或创建 PR                    |   必须 | Human-in-the-loop |
| PR 创建      | 调 GitHub API 创建 Pull Request        |   必须 | 完整闭环              |

GitHub REST API 官方支持 Pull Request 的创建、查询、更新、合并等操作，所以你的 PR 自动化闭环是可实现的。([GitHub Docs][5])

---

## 4.2 进阶功能

| 功能              | 描述                                 | 优先级 | 加分点            |
| --------------- | ---------------------------------- | --: | -------------- |
| PR Review Agent | 自动审查 PR 风险                         |   高 | 很适合面试展示        |
| API 文档生成        | 扫描 Controller 自动生成接口文档             |   高 | Java 后端强相关     |
| SQL 变更 Agent    | 根据需求生成 migration、Entity、Mapper 修改  |   中 | 后端业务开发场景       |
| 权限风险检查          | 检查接口是否缺少鉴权、参数校验                    |   中 | 可结合网络安全经历      |
| 调用链分析           | Controller → Service → Mapper → DB |   高 | 体现 AST + 结构化分析 |
| WebSocket 实时日志  | Agent 执行过程实时展示                     |   高 | 后端工程体验         |
| 多模型配置           | 支持 OpenAI、Claude、Qwen、DeepSeek     |   中 | 平台化能力          |
| 多 Agent 协作      | Planner、Coder、Tester、Reviewer 分工   |   高 | Agent 项目核心卖点   |
| MCP Tool 管理台    | 展示当前可用工具、调用次数、耗时                   |   中 | 前沿工程感          |
| 回滚机制            | 失败后恢复代码工作区                         |   中 | 工程可靠性          |

---

# 5. 技术栈总表

我建议你采用：

> **Spring Boot 主平台 + Agent Worker + MCP 工具层 + Docker 沙箱 + GitHub API**

## 推荐技术栈

| 层级         | 技术选择                                | 说明                                |
| ---------- | ----------------------------------- | --------------------------------- |
| 前端         | React / Vue 3                       | 做任务控制台、日志展示、diff 预览               |
| 后端主框架      | Spring Boot 3                       | 项目核心，体现 Java 后端能力                 |
| 鉴权         | Spring Security + JWT 或 Sa-Token    | 简历里能写权限系统                         |
| 数据库        | PostgreSQL                          | 存项目、任务、日志、审批、PR 信息                |
| 向量检索       | pgvector 或 Qdrant                   | 代码库 RAG                           |
| 缓存/队列      | Redis                               | 异步任务状态、锁、缓存                       |
| ORM        | MyBatis-Plus / JPA                  | Java 后端常规能力                       |
| Agent 编排   | LangGraph 或 Spring AI Alibaba Graph | 第一版建议 LangGraph，后续可迁移 Java 原生     |
| Java AI 框架 | Spring AI                           | 用于 LLM 调用、工具调用、MCP                |
| MCP        | Spring AI MCP Server                | 把 Java 工具暴露给 Agent                |
| 代码解析       | JavaParser / Tree-sitter            | 解析 Java AST、类、方法、注解               |
| 沙箱         | Docker                              | 隔离执行 mvn test、git diff            |
| 代码管理       | Git CLI / JGit                      | clone、checkout、branch、diff、commit |
| 外部平台       | GitHub REST API                     | 创建 Issue、PR、读取文件                  |
| 实时通信       | SSE / WebSocket                     | 展示 Agent 执行进度                     |
| 日志追踪       | 自研 AgentRunLog                      | 记录 tool call、prompt、耗时、状态         |
| 部署         | Docker Compose                      | 一键启动项目                            |

JavaParser 官方项目支持 Java 代码解析和高级分析能力，适合用来抽取 Java 类、方法、注解、调用结构。([GitHub][6]) pgvector 可以直接在 PostgreSQL 中存储向量并做相似度搜索，适合你这种中小型简历项目。([GitHub][7]) 如果你想做更专业的检索服务，Qdrant 官方支持 dense、sparse、multivector 混合查询，也适合代码检索场景。([Qdrant][8])

---

# 6. 两条技术路线对比

## 路线 A：纯 Java Agent 路线

| 项目   | 内容                                                      |
| ---- | ------------------------------------------------------- |
| 架构   | Spring Boot + Spring AI + Spring AI Alibaba Graph + MCP |
| 优点   | 简历上非常贴 Java 后端                                          |
| 缺点   | Agent 编排资料相对少，容易踩坑                                      |
| 适合   | 你明确只投 Java 后端                                           |
| 推荐程度 | 8/10                                                    |

Spring AI Alibaba 官方定位是 Java Agentic AI Framework，并提供 Agent Framework、Graph、多 Agent、Workflow、MCP 管理和可观测能力。([GitHub][9])

---

## 路线 B：Java 平台 + Python Agent Worker

| 项目   | 内容                                                       |
| ---- | -------------------------------------------------------- |
| 架构   | Spring Boot 主控 + Python LangGraph Worker + MCP/HTTP 工具调用 |
| 优点   | 更容易跑通，Agent 生态成熟，资料最多                                    |
| 缺点   | 不是纯 Java                                                 |
| 适合   | 同时投 Agent 和 Java 后端                                      |
| 推荐程度 | 9/10                                                     |

LangGraph 是目前 Agent 编排里很主流的选择，官方强调 durable execution、streaming、human-in-the-loop、persistence 等能力，适合做长任务 Agent 工作流。([Docs by LangChain][1])

---

## 我的建议

你先走路线 B：

```text
Spring Boot 负责平台工程能力
Python LangGraph 负责 Agent 编排
Spring AI MCP Server 负责 Java 工具暴露
Docker 负责代码执行沙箱
```

等 MVP 跑通后，再做一个“Java 原生 Agent 版本”的分支或模块，用 Spring AI Alibaba Graph 实现部分流程。这样你投简历时可以两边都讲。

---

# 7. 系统架构设计

## 7.1 总体架构图

```text
┌────────────────────────────────────────────┐
│                Web 前端                     │
│  项目管理 / 任务创建 / 日志查看 / Diff审批   │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│             Spring Boot 后端主平台          │
│  用户鉴权 / 项目管理 / 任务状态 / 审批 / API │
└────────────────────┬───────────────────────┘
                     │
          ┌──────────┴──────────┐
          ▼                     ▼
┌───────────────────┐   ┌────────────────────┐
│ Agent Orchestrator │   │  MCP Tool Server    │
│ LangGraph / Graph  │   │  Spring AI MCP      │
└─────────┬─────────┘   └─────────┬──────────┘
          │                       │
          ▼                       ▼
┌────────────────────────────────────────────┐
│              工具能力层                     │
│ read_file / search_code / run_test / git_diff│
│ apply_patch / create_pr / parse_ast          │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│             Docker Sandbox                  │
│ clone repo / checkout branch / mvn test      │
└────────────────────┬───────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────┐
│ PostgreSQL + pgvector / Redis / GitHub API   │
└────────────────────────────────────────────┘
```

---

# 8. Agent 工作流设计

## 8.1 多 Agent 角色表

| Agent             | 职责       | 输入           | 输出           |
| ----------------- | -------- | ------------ | ------------ |
| PlannerAgent      | 理解需求，拆任务 | 用户任务、项目元信息   | 执行计划         |
| RepoAnalyzerAgent | 分析项目结构   | 仓库目录、AST 索引  | 项目结构摘要       |
| RetrieverAgent    | 检索相关代码   | 任务、关键词、调用链   | 相关文件列表       |
| CoderAgent        | 生成代码修改   | 上下文、计划       | unified diff |
| TestAgent         | 执行测试     | diff、仓库路径    | 测试结果         |
| RepairAgent       | 根据失败日志修复 | 测试日志、diff    | 新 diff       |
| ReviewAgent       | 审查代码风险   | diff、上下文     | 风险报告         |
| PRAgent           | 生成 PR 信息 | 最终 diff、测试结果 | PR 标题和描述     |

---

## 8.2 Agent 状态机

```text
CREATED
  ↓
REPO_INDEXING
  ↓
PLANNING
  ↓
RETRIEVING_CONTEXT
  ↓
GENERATING_PATCH
  ↓
APPLYING_PATCH_IN_SANDBOX
  ↓
RUNNING_TESTS
  ↓
TEST_FAILED? → REPAIRING → RUNNING_TESTS
  ↓
REVIEWING_PATCH
  ↓
WAITING_HUMAN_APPROVAL
  ↓
CREATING_PULL_REQUEST
  ↓
DONE
```

失败状态：

```text
FAILED_REPO_CLONE
FAILED_INDEXING
FAILED_CONTEXT_RETRIEVAL
FAILED_PATCH_GENERATION
FAILED_TEST
FAILED_PR_CREATION
CANCELLED
```

---

# 9. MCP 工具设计表

MCP 工具层是你这个项目的核心亮点之一。Spring AI MCP Server Boot Starter 支持在 Spring Boot 中暴露 MCP Server 能力，所以你可以把下面这些能力封装成工具。([Home][3])

| Tool 名称               | 功能                                   | 是否 MVP |
| --------------------- | ------------------------------------ | -----: |
| `list_project_files`  | 列出仓库文件树                              |     必须 |
| `read_file`           | 读取指定文件内容                             |     必须 |
| `search_code`         | 基于关键词/向量检索代码                         |     必须 |
| `get_class_structure` | 获取类、方法、字段、注解                         |     必须 |
| `find_controller_api` | 查找 Spring Controller 接口              |     必须 |
| `find_call_chain`     | 查找 Controller → Service → Mapper 调用链 |     进阶 |
| `apply_patch`         | 在沙箱中应用 diff                          |     必须 |
| `get_git_diff`        | 获取当前代码 diff                          |     必须 |
| `run_maven_test`      | 执行 `mvn test`                        |     必须 |
| `run_maven_compile`   | 执行 `mvn compile`                     |     必须 |
| `create_branch`       | 创建新分支                                |     必须 |
| `commit_changes`      | 提交修改                                 |     必须 |
| `create_pull_request` | 创建 GitHub PR                         |     必须 |
| `query_db_schema`     | 查询数据库表结构                             |     进阶 |
| `generate_api_doc`    | 生成接口文档                               |     进阶 |
| `run_security_check`  | 检查 SQL 注入、鉴权、参数校验                    |     进阶 |

---

# 10. 后端模块拆分表

| 模块         | 包名建议           | 主要职责                            |
| ---------- | -------------- | ------------------------------- |
| 用户模块       | `user`         | 注册、登录、权限                        |
| 项目模块       | `project`      | 管理 GitHub 仓库                    |
| 仓库模块       | `repository`   | clone、pull、branch、commit        |
| 索引模块       | `indexer`      | AST 解析、代码切片、向量化                 |
| Agent 任务模块 | `agent`        | 创建任务、状态流转、Agent 调用              |
| 工具模块       | `tool`         | MCP tools、Git tools、Maven tools |
| 沙箱模块       | `sandbox`      | Docker 容器创建、执行、销毁               |
| 审批模块       | `approval`     | 人工审批、拒绝、重新生成                    |
| PR 模块      | `pullrequest`  | GitHub PR 创建和状态同步               |
| 日志模块       | `trace`        | tool call log、执行日志、耗时           |
| 通知模块       | `notification` | SSE/WebSocket 实时推送              |
| 配置模块       | `config`       | 模型、GitHub token、Docker 配置       |

---

# 11. 数据库设计表

## 11.1 核心表

| 表名                    | 作用          |
| --------------------- | ----------- |
| `user`                | 用户信息        |
| `project`             | 用户接入的项目     |
| `repository_snapshot` | 仓库快照信息      |
| `code_file`           | 文件元信息       |
| `code_symbol`         | 类、方法、字段、注解  |
| `code_chunk`          | 代码切片        |
| `code_embedding`      | 代码向量        |
| `agent_task`          | Agent 任务    |
| `agent_run`           | 一次 Agent 执行 |
| `agent_step`          | Agent 执行步骤  |
| `tool_call_log`       | 工具调用日志      |
| `patch_record`        | 生成的 diff    |
| `test_run`            | 测试执行结果      |
| `approval_record`     | 人工审批记录      |
| `pull_request_record` | PR 记录       |
| `model_call_log`      | 模型调用日志      |

---

## 11.2 关键表字段

### `agent_task`

| 字段               | 类型        | 说明                              |
| ---------------- | --------- | ------------------------------- |
| `id`             | bigint    | 主键                              |
| `project_id`     | bigint    | 所属项目                            |
| `user_id`        | bigint    | 创建者                             |
| `task_type`      | varchar   | FEATURE / BUGFIX / REVIEW / DOC |
| `title`          | varchar   | 任务标题                            |
| `description`    | text      | 用户输入                            |
| `status`         | varchar   | 当前状态                            |
| `current_run_id` | bigint    | 当前执行记录                          |
| `created_at`     | timestamp | 创建时间                            |
| `updated_at`     | timestamp | 更新时间                            |

---

### `tool_call_log`

| 字段              | 类型        | 说明               |
| --------------- | --------- | ---------------- |
| `id`            | bigint    | 主键               |
| `agent_run_id`  | bigint    | 执行 ID            |
| `step_id`       | bigint    | 步骤 ID            |
| `tool_name`     | varchar   | 工具名              |
| `input_json`    | jsonb     | 工具输入             |
| `output_json`   | jsonb     | 工具输出             |
| `status`        | varchar   | SUCCESS / FAILED |
| `duration_ms`   | int       | 耗时               |
| `error_message` | text      | 错误信息             |
| `created_at`    | timestamp | 创建时间             |

---

### `patch_record`

| 字段              | 类型        | 说明                                        |
| --------------- | --------- | ----------------------------------------- |
| `id`            | bigint    | 主键                                        |
| `agent_task_id` | bigint    | 任务 ID                                     |
| `agent_run_id`  | bigint    | 执行 ID                                     |
| `base_branch`   | varchar   | 基础分支                                      |
| `target_branch` | varchar   | 修改分支                                      |
| `diff_content`  | text      | unified diff                              |
| `summary`       | text      | 修改摘要                                      |
| `status`        | varchar   | GENERATED / APPLIED / APPROVED / REJECTED |
| `created_at`    | timestamp | 创建时间                                      |

---

# 12. API 接口设计

## 12.1 用户与项目

| 方法     | 路径                           | 功能           |
| ------ | ---------------------------- | ------------ |
| `POST` | `/api/auth/register`         | 注册           |
| `POST` | `/api/auth/login`            | 登录           |
| `POST` | `/api/projects`              | 添加 GitHub 仓库 |
| `GET`  | `/api/projects`              | 项目列表         |
| `GET`  | `/api/projects/{id}`         | 项目详情         |
| `POST` | `/api/projects/{id}/index`   | 触发代码索引       |
| `GET`  | `/api/projects/{id}/files`   | 查看文件树        |
| `GET`  | `/api/projects/{id}/symbols` | 查看代码符号       |

---

## 12.2 Agent 任务

| 方法     | 路径                             | 功能          |
| ------ | ------------------------------ | ----------- |
| `POST` | `/api/agent/tasks`             | 创建 Agent 任务 |
| `GET`  | `/api/agent/tasks`             | 任务列表        |
| `GET`  | `/api/agent/tasks/{id}`        | 任务详情        |
| `POST` | `/api/agent/tasks/{id}/run`    | 启动任务        |
| `POST` | `/api/agent/tasks/{id}/cancel` | 取消任务        |
| `GET`  | `/api/agent/tasks/{id}/steps`  | 查看执行步骤      |
| `GET`  | `/api/agent/tasks/{id}/logs`   | 查看日志        |
| `GET`  | `/api/agent/tasks/{id}/stream` | SSE 实时日志    |

---

## 12.3 Patch 与审批

| 方法     | 路径                                 | 功能        |
| ------ | ---------------------------------- | --------- |
| `GET`  | `/api/tasks/{id}/patch`            | 查看 diff   |
| `POST` | `/api/tasks/{id}/patch/regenerate` | 重新生成 diff |
| `POST` | `/api/tasks/{id}/approval/approve` | 审批通过      |
| `POST` | `/api/tasks/{id}/approval/reject`  | 拒绝修改      |
| `POST` | `/api/tasks/{id}/pull-request`     | 创建 PR     |
| `GET`  | `/api/tasks/{id}/pull-request`     | 查看 PR 状态  |

---

# 13. 前端页面规划

| 页面        | 功能                                     |
| --------- | -------------------------------------- |
| 登录页       | 用户登录                                   |
| 项目列表页     | 查看已接入仓库                                |
| 添加项目页     | 输入 GitHub repo URL、token               |
| 项目详情页     | 文件树、技术栈、Controller 列表、Service 列表       |
| 代码问答页     | 基于仓库问问题                                |
| Agent 任务页 | 创建任务、选择任务类型                            |
| Agent 执行页 | 实时展示 Planner、Retriever、Coder、Tester 步骤 |
| Diff 预览页  | 展示修改文件、diff 高亮                         |
| 测试日志页     | 展示 Maven 测试结果                          |
| 审批页       | Approve / Reject / Regenerate          |
| PR 结果页    | 展示 GitHub PR 链接                        |
| 工具调用追踪页   | 每次 tool call 的输入输出、耗时、状态               |
| 系统配置页     | 模型 API、GitHub Token、Docker 配置          |

---

# 14. 代码仓库结构规划

建议你的项目仓库这样组织：

```text
repopilot/
├── README.md
├── docker-compose.yml
├── docs/
│   ├── architecture.md
│   ├── agent-workflow.md
│   ├── mcp-tools.md
│   ├── database-design.md
│   ├── api-design.md
│   └── demo-script.md
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/repopilot/
│       ├── RepopilotApplication.java
│       ├── auth/
│       ├── user/
│       ├── project/
│       ├── repo/
│       ├── indexer/
│       ├── agent/
│       ├── sandbox/
│       ├── tool/
│       ├── approval/
│       ├── pullrequest/
│       ├── trace/
│       └── common/
├── agent-worker/
│   ├── requirements.txt
│   ├── main.py
│   ├── graph/
│   │   ├── workflow.py
│   │   ├── planner.py
│   │   ├── retriever.py
│   │   ├── coder.py
│   │   ├── tester.py
│   │   └── reviewer.py
│   └── clients/
│       ├── mcp_client.py
│       └── backend_client.py
├── frontend/
│   ├── package.json
│   └── src/
│       ├── pages/
│       ├── components/
│       └── api/
└── examples/
    └── demo-springboot-project/
```

---

# 15. 开发排期总表

我建议你按 **10 周完整版本**来做。如果时间紧，前 5 周做出 MVP 就能写简历。

## 第 0 阶段：项目准备

| 内容                | 目标                         |
| ----------------- | -------------------------- |
| 确定项目名称            | RepoPilot                  |
| 建 GitHub 仓库       | 写好 README 初版               |
| 准备 Demo 项目        | 一个简单 Spring Boot CRUD 项目   |
| 准备技术文档            | architecture.md、roadmap.md |
| 配置 Docker Compose | PostgreSQL、Redis、后端、前端     |

---

## 第 1 周：后端基础框架

| 任务                | 产出           |
| ----------------- | ------------ |
| 搭建 Spring Boot 项目 | 后端骨架         |
| 接入 PostgreSQL     | 数据库可用        |
| 接入 Redis          | 缓存和任务状态可用    |
| 实现用户登录            | JWT 鉴权       |
| 实现项目表             | Project CRUD |
| 实现统一返回和异常处理       | 标准后端工程结构     |

验收标准：

```text
用户可以登录
用户可以添加一个 GitHub 仓库地址
项目数据能保存到数据库
```

---

## 第 2 周：Git 仓库接入

| 任务                 | 产出               |
| ------------------ | ---------------- |
| 实现 clone repo      | 仓库可拉取到 workspace |
| 实现 branch 操作       | 可创建任务分支          |
| 实现 git diff        | 可获取修改差异          |
| 实现 workspace 管理    | 每个项目有独立工作区       |
| 实现 GitHub token 配置 | 可访问私有仓库，可选       |

验收标准：

```text
输入 GitHub 仓库地址
系统能 clone 到本地
页面能看到文件树
```

---

## 第 3 周：Java 项目解析

| 任务                     | 产出                 |
| ---------------------- | ------------------ |
| 接入 JavaParser          | 解析 Java 文件         |
| 解析 Controller          | 提取 API path、method |
| 解析 Service             | 提取 service 类和方法    |
| 解析 Entity / DTO        | 提取字段               |
| 解析 Mapper / Repository | 提取数据访问层            |
| 存入 code_symbol 表       | 形成结构化代码索引          |

验收标准：

```text
系统能识别：
- 有哪些 Controller
- 有哪些接口
- 每个类有哪些方法
- 每个方法在哪个文件哪一行
```

---

## 第 4 周：代码 RAG 检索

| 任务                   | 产出              |
| -------------------- | --------------- |
| 代码切片                 | 按类、方法、注释切 chunk |
| 接入 embedding 模型      | 生成代码向量          |
| 接入 pgvector / Qdrant | 存储向量            |
| 实现 search_code       | 根据任务检索相关代码      |
| 实现代码问答               | 用户可问项目结构问题      |

验收标准：

```text
用户问：
“登录接口在哪里？”
系统能返回：
UserController.login()
AuthService.login()
JwtUtil.generateToken()
```

---

## 第 5 周：Agent MVP 工作流

| 任务                | 产出                 |
| ----------------- | ------------------ |
| 搭建 Agent Worker   | LangGraph / Python |
| 实现 PlannerAgent   | 生成任务计划             |
| 实现 RetrieverAgent | 调用 search_code     |
| 实现 CoderAgent     | 生成 diff            |
| 后端创建 agent_task   | 任务状态可记录            |
| SSE 推送日志          | 前端能看到执行过程          |

验收标准：

```text
输入：
“帮我给用户模块新增分页查询接口”

系统输出：
1. 修改计划
2. 相关文件
3. 代码 diff
```

---

## 第 6 周：Docker 沙箱执行

| 任务                  | 产出            |
| ------------------- | ------------- |
| 实现 Docker workspace | 每个任务独立容器      |
| 实现 apply_patch      | 在容器里应用 diff   |
| 实现 mvn compile      | 编译检查          |
| 实现 mvn test         | 单元测试          |
| 收集测试日志              | 存入 test_run 表 |
| 失败状态处理              | 返回错误原因        |

验收标准：

```text
Agent 生成 diff 后
系统能在 Docker 容器里应用 patch
执行 mvn test
返回测试结果
```

---

## 第 7 周：失败修复与 Review Agent

| 任务                  | 产出          |
| ------------------- | ----------- |
| TestAgent 分析日志      | 总结失败原因      |
| RepairAgent 修复 diff | 根据错误日志改代码   |
| 限制最大重试次数            | 防止死循环       |
| ReviewAgent 审查 diff | 检查安全、风格、缺测试 |
| 输出风险报告              | 展示给用户       |

验收标准：

```text
如果第一次代码编译失败
Agent 能读取日志
重新生成修复版 diff
最多重试 2 次
```

---

## 第 8 周：人工审批与 PR 创建

| 任务                 | 产出                |
| ------------------ | ----------------- |
| Diff 预览页           | 用户查看修改            |
| Approve / Reject   | 人工审批              |
| 创建分支               | `agent/task-{id}` |
| commit 修改          | 自动提交              |
| 调 GitHub API 创建 PR | 完成闭环              |
| 记录 PR 链接           | 展示给用户             |

验收标准：

```text
用户点击 Approve
系统创建 GitHub PR
页面展示 PR 链接
```

---

## 第 9 周：可观测性与后台管理

| 任务              | 产出         |
| --------------- | ---------- |
| Tool call trace | 记录每次工具调用   |
| Model call log  | 记录模型输入输出摘要 |
| Token / cost 统计 | 展示成本       |
| Agent 时间线       | 每一步耗时      |
| 任务失败诊断          | 错误原因归类     |
| MCP 工具管理页       | 展示可用工具     |

验收标准：

```text
用户能看到：
Planner 调了什么
Retriever 找了哪些文件
Coder 生成了什么 diff
Tester 执行了什么命令
每一步花了多久
```

---

## 第 10 周：项目包装与简历化

| 任务         | 产出                     |
| ---------- | ---------------------- |
| README 完整化 | 项目介绍、架构图、启动方式          |
| 写技术文档      | Agent 工作流、MCP 工具、数据库设计 |
| 录制 Demo 视频 | 3–5 分钟                 |
| 准备测试仓库     | 用于演示                   |
| 写简历 bullet | 中英文两版                  |
| 准备面试讲解稿    | 2 分钟 / 5 分钟版本          |

验收标准：

```text
项目能部署
README 能看懂
Demo 能跑通
简历能写
面试能讲
```

---

# 16. MVP 范围控制

你第一版千万不要贪多。先只支持这三类任务：

| 任务类型     | 示例                 | 是否第一版支持 |
| -------- | ------------------ | ------: |
| 代码问答     | “登录流程在哪里？”         |      支持 |
| 新增简单接口   | “给 User 新增分页查询接口”  |      支持 |
| 修复简单 bug | “这个接口 NPE，帮我定位并修复” |      支持 |
| 大规模重构    | “把整个项目改成微服务”       |     不支持 |
| 数据库复杂迁移  | “重构订单系统表结构”        |     不支持 |
| 多仓库联动    | “同时改前后端”           |     不支持 |
| 自动部署上线   | “改完直接发生产”          |     不支持 |

MVP 的核心不是“万能”，而是把一个闭环做完整。

---

# 17. Demo 项目设计

你需要准备一个自己的 Spring Boot Demo 项目，作为 RepoPilot 的测试对象。

## Demo 项目名称

**MiniMall Backend**

功能很简单：

```text
用户模块
商品模块
订单模块
登录鉴权
分页查询
MyBatis-Plus
MySQL / H2
JUnit 测试
```

这样 RepoPilot 可以演示：

```text
任务 1：帮我分析登录流程
任务 2：帮我给商品模块新增分页查询接口
任务 3：帮我检查订单创建接口是否缺少参数校验
任务 4：帮我修复 UserService 里的 NPE
任务 5：帮我生成 API 文档
```

---

# 18. 最佳演示脚本

你之后面试或者放 GitHub，可以这样演示：

## 演示 1：代码理解

输入：

```text
这个项目的登录流程在哪里？请说明 Controller、Service、JWT 生成逻辑。
```

系统输出：

```text
1. AuthController.login()
2. AuthService.login()
3. UserMapper.selectByUsername()
4. PasswordEncoder.matches()
5. JwtUtil.generateToken()
```

展示价值：

```text
代码库 RAG + AST 结构分析
```

---

## 演示 2：自动新增接口

输入：

```text
请给商品模块新增一个分页查询接口，支持 keyword、categoryId、minPrice、maxPrice 查询条件，并补充单元测试。
```

系统输出：

```text
修改计划
相关文件
生成 diff
Docker 测试通过
等待审批
创建 PR
```

展示价值：

```text
Agent 工作流 + 代码生成 + 沙箱测试 + PR 闭环
```

---

## 演示 3：PR Review

输入：

```text
请审查这个 PR 是否存在安全风险、参数校验缺失、NPE 风险和测试缺失。
```

系统输出：

```text
风险 1：ProductController 缺少 @Validated
风险 2：price 参数没有边界检查
风险 3：ProductService 中 category 可能为空
建议补充测试：ProductQueryTest
```

展示价值：

```text
AI Code Review + 后端安全意识
```

---

# 19. 简历包装方式

## 中文版

```text
RepoPilot：面向 Java/Spring Boot 仓库的 AI 软件工程 Agent 平台

- 设计并实现 AI 软件工程 Agent 平台，支持 GitHub 仓库接入、代码结构解析、Issue 分析、代码检索、补丁生成、沙箱测试、人工审批与 Pull Request 创建。
- 基于 Spring Boot 构建后端控制平面，实现用户鉴权、项目管理、异步任务状态机、SSE 实时日志推送、审批流与执行审计。
- 基于 JavaParser 构建 Java 代码 AST 索引，提取 Controller、Service、Entity、Mapper 等结构信息，并结合向量检索实现代码库级上下文召回。
- 通过 MCP 工具层封装文件读取、代码搜索、Git 操作、Maven 测试、Diff 生成和 PR 创建能力，供 Agent 在任务执行过程中动态调用。
- 使用 Docker 沙箱隔离执行 AI 生成补丁，自动完成 Maven 编译、单元测试、失败日志分析与有限重试，降低 Agent 自动改代码风险。
```

---

## 英文版

```text
RepoPilot: Agentic Backend Maintenance Platform for Java/Spring Boot Repositories

- Designed and implemented an AI software engineering agent platform for Java/Spring Boot repositories, supporting repository indexing, issue analysis, code retrieval, patch generation, sandboxed testing, human approval, and GitHub Pull Request creation.
- Built a Spring Boot control plane with authentication, project management, asynchronous task state machine, SSE-based execution streaming, approval workflow, and audit logging.
- Implemented Java codebase indexing with JavaParser-based AST extraction and vector retrieval to provide repository-level context for agent planning and code modification.
- Exposed repository operations, file reading, code search, Git commands, Maven testing, diff generation, and PR creation as MCP-compatible tools for agent execution.
- Integrated Docker-based isolated execution to compile, test, and validate AI-generated patches before human approval.
```

---

# 20. 项目难点和解决方案

| 难点         | 风险                | 解决方案                             |
| ---------- | ----------------- | -------------------------------- |
| Agent 乱改代码 | 生成不可控             | 只生成 diff，不直接覆盖                   |
| 测试执行有风险    | 命令可能污染本机          | Docker 沙箱隔离                      |
| 上下文太大      | LLM 看不过来          | AST 索引 + 向量检索                    |
| 代码检索不准     | 找错文件              | 语义检索 + 关键词 + 类/方法结构索引            |
| 任务太复杂      | Agent 跑偏          | 限制 MVP 任务类型                      |
| PR 创建失败    | GitHub token 权限问题 | 先支持 public repo，再支持 private repo |
| 成本太高       | 多轮调用花钱            | 限制最大轮数、缓存索引、记录 token             |
| 面试被质疑套壳    | 技术含量不明显           | 强调 MCP、沙箱、状态机、AST、审批、日志          |

---

# 21. 你应该优先做哪些亮点

按简历价值排序：

| 排名 | 亮点                | 原因                  |
| -: | ----------------- | ------------------- |
|  1 | Docker 沙箱执行       | 一下子和普通 AI Demo 拉开差距 |
|  2 | Agent 状态机         | 体现平台工程能力            |
|  3 | AST + 向量检索        | 不是简单文档 RAG          |
|  4 | MCP 工具层           | 当前 Agent 工程化前沿      |
|  5 | Human-in-the-loop | 真实生产系统思维            |
|  6 | GitHub PR 闭环      | 展示完整工程流             |
|  7 | Tool call trace   | 面试非常好讲              |
|  8 | PR Review Agent   | 适合展示安全和后端理解         |

---

# 22. 最终版本功能清单

你最终项目最好做到这个程度：

```text
基础后端：
[√] 登录注册
[√] 项目管理
[√] GitHub 仓库接入
[√] 异步任务
[√] SSE 实时日志
[√] 审批流
[√] 操作审计

代码理解：
[√] Java 文件扫描
[√] Controller / Service / Mapper 解析
[√] AST 结构索引
[√] 代码 chunk 切分
[√] 向量检索
[√] 代码问答

Agent：
[√] PlannerAgent
[√] RetrieverAgent
[√] CoderAgent
[√] TestAgent
[√] RepairAgent
[√] ReviewAgent
[√] PRAgent

工具：
[√] read_file
[√] search_code
[√] get_class_structure
[√] apply_patch
[√] run_maven_test
[√] get_git_diff
[√] create_pull_request

工程化：
[√] Docker 沙箱
[√] Tool call trace
[√] Agent timeline
[√] Patch diff preview
[√] Human approval
[√] PR creation
```

---

# 23. 最小可行版本功能清单

时间不够就只做这个：

```text
[√] Spring Boot 后端
[√] 项目接入 GitHub repo
[√] Java 项目文件扫描
[√] Controller / Service 解析
[√] 代码向量检索
[√] 用户创建 Agent 任务
[√] Agent 输出修改计划
[√] Agent 生成 diff
[√] Docker 执行 mvn test
[√] 页面展示 diff 和测试日志
```

只要这 10 个点做出来，已经可以作为简历项目。

---

# 24. 不建议你做的方向

| 方向                   | 为什么不建议              |
| -------------------- | ------------------- |
| AI 知识库问答             | 太烂大街，简历区分度低         |
| AI 简历助手              | 业务太轻，后端技术含量低        |
| AI 日程助手              | Agent 感不强，容易像普通工具   |
| Dify 二开              | 容易被认为只是配置平台         |
| LangChain 普通 Chatbot | 太基础                 |
| 企业客服 Agent           | 同质化严重               |
| 论文阅读 Agent           | 更适合科研，不如这个贴 Java 后端 |
| 多 Agent 辩论系统         | 花哨但工程价值弱            |

---

# 25. 你这个项目的最终定位

你要把它包装成：

> **不是一个 AI 聊天项目，而是一个面向软件工程场景的 Agentic Backend Platform。**

面试时你重点讲：

```text
1. 我不是让 LLM 直接乱改代码，而是通过状态机控制 Agent 流程。
2. 我不是简单把整个代码塞给模型，而是做了 AST 结构索引和向量检索。
3. 我不是直接执行模型命令，而是通过 MCP 工具层暴露受控能力。
4. 我不是在宿主机运行代码，而是在 Docker 沙箱里验证。
5. 我不是自动合并，而是 Human-in-the-loop 审批后创建 PR。
6. 我做了 tool call trace，可以追踪每一步 Agent 做了什么。
```

这几个点一讲出来，项目就不是普通“AI 套壳”了。

---

# 26. 最终建议路线

你现在就按这个顺序推进：

```text
第一阶段：Spring Boot 后端平台
第二阶段：GitHub 仓库接入
第三阶段：Java AST 解析
第四阶段：代码向量检索
第五阶段：Agent 任务工作流
第六阶段：diff 生成
第七阶段：Docker 测试
第八阶段：人工审批
第九阶段：GitHub PR 创建
第十阶段：可观测性和项目包装
```

最重要的一句话：

> **先做闭环，不要先追求智能。**

也就是说，第一版 Agent 哪怕不够聪明也没关系，只要能完整跑通：

```text
任务输入 → 找代码 → 生成 diff → 跑测试 → 审批 → 创建 PR
```

这个闭环跑通后，再慢慢提高 Agent 的准确率。

[1]: https://docs.langchain.com/oss/python/langgraph/overview?utm_source=chatgpt.com "LangGraph overview - Docs by LangChain"
[2]: https://www.anthropic.com/news/model-context-protocol?utm_source=chatgpt.com "Introducing the Model Context Protocol"
[3]: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html?utm_source=chatgpt.com "MCP Server Boot Starter"
[4]: https://docs.openhands.dev/sdk/guides/agent-server/docker-sandbox?utm_source=chatgpt.com "Docker Sandbox"
[5]: https://docs.github.com/en/rest/pulls?utm_source=chatgpt.com "REST API endpoints for pull requests"
[6]: https://github.com/javaparser/javaparser?utm_source=chatgpt.com "JavaParser"
[7]: https://github.com/pgvector/pgvector?utm_source=chatgpt.com "pgvector/pgvector: Open-source vector similarity search for ..."
[8]: https://qdrant.tech/documentation/search/hybrid-queries/?utm_source=chatgpt.com "Hybrid Queries"
[9]: https://github.com/alibaba/spring-ai-alibaba?utm_source=chatgpt.com "alibaba/spring-ai-alibaba: Agentic AI Framework for Java ..."
