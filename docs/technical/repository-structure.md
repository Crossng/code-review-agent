# 仓库结构规范

## 1. 目标结构

RepoPilot 使用多模块目录组织，先保证本地开发清晰，再逐步拆分部署。

```text
repopilot/
├── README.md
├── docker-compose.yml
├── docs/
├── backend/
├── agent-worker/
├── frontend/
├── mcp-tool-server/
├── scripts/
├── examples/
└── workspace/
```

## 2. 顶层目录说明

| 目录 | 说明 | MVP 是否需要 |
| --- | --- | --- |
| `docs/` | 需求、技术和管理文档 | 是 |
| `backend/` | Spring Boot 主平台 | 是 |
| `agent-worker/` | Python LangGraph Worker | 是 |
| `frontend/` | Web 控制台 | 是 |
| `mcp-tool-server/` | Spring AI MCP 工具服务，可先与 backend 合并，后续拆出 | 是 |
| `scripts/` | 开发、索引、演示、清理脚本 | 是 |
| `examples/` | Demo 仓库配置、示例任务、演示数据 | 是 |
| `workspace/` | 本地运行工作区，放 clone 仓库和 run 临时目录，不提交 Git | 是 |

## 3. Backend 结构

```text
backend/
├── pom.xml
├── src/main/java/com/repopilot/
│   ├── RepopilotApplication.java
│   ├── auth/
│   ├── user/
│   ├── project/
│   ├── repository/
│   ├── indexer/
│   ├── agent/
│   ├── tool/
│   ├── sandbox/
│   ├── approval/
│   ├── pullrequest/
│   ├── trace/
│   ├── notification/
│   ├── config/
│   └── common/
└── src/test/java/com/repopilot/
```

Backend 命名约定：

- Controller 类名以 `Controller` 结尾。
- Service 接口以 `Service` 结尾，实现类以 `ServiceImpl` 结尾。
- 数据库访问层以 `Repository` 或 `Mapper` 结尾。
- 请求 DTO 以 `Request` 结尾，响应 DTO 以 `Response` 结尾。
- 状态枚举以 `Status` 结尾。

## 4. Agent Worker 结构

```text
agent-worker/
├── pyproject.toml
├── README.md
├── app/
│   ├── main.py
│   ├── config.py
│   ├── graph/
│   │   ├── builder.py
│   │   ├── state.py
│   │   └── nodes/
│   ├── agents/
│   ├── clients/
│   ├── prompts/
│   └── schemas/
└── tests/
```

Agent Worker 约定：

- `graph/state.py` 定义 `AgentRunState`。
- `graph/nodes/` 每个 LangGraph 节点一个文件。
- `clients/` 只封装 Backend API、MCP、LLM 调用。
- `prompts/` 保存 Planner、Coder、Repair、Review 模板。
- 所有节点输出必须可 JSON 序列化，方便回写后端。

## 5. Frontend 结构

```text
frontend/
├── package.json
├── src/
│   ├── app/
│   ├── pages/
│   ├── components/
│   ├── features/
│   ├── api/
│   ├── hooks/
│   ├── stores/
│   └── styles/
└── tests/
```

Frontend 约定：

- `features/project` 管理项目接入和项目详情。
- `features/agent` 管理任务、步骤、日志流。
- `features/patch` 管理 diff 预览和审批。
- `features/settings` 管理模型、GitHub、Docker 配置。

## 6. MCP Tool Server 结构

```text
mcp-tool-server/
├── pom.xml
└── src/main/java/com/repopilot/toolserver/
    ├── ToolServerApplication.java
    ├── file/
    ├── javaast/
    ├── search/
    ├── git/
    ├── maven/
    ├── docker/
    ├── github/
    └── audit/
```

MVP 可以先把工具服务放在 `backend/tool` 中，等工具数量稳定后再拆成独立 `mcp-tool-server/`。

## 7. Workspace 约定

```text
workspace/
├── repos/{projectId}/source/
└── runs/{runId}/source/
```

`workspace/` 必须加入 `.gitignore`，避免提交用户仓库、测试日志、密钥和临时构建产物。

## 8. 配置文件

建议配置：

```text
.env.example
backend/src/main/resources/application.yml
backend/src/main/resources/application-local.yml
agent-worker/.env.example
frontend/.env.example
```

配置原则：

- 示例配置提交，真实密钥不提交。
- 所有 token 和 key 支持环境变量注入。
- 本地开发默认指向 Docker Compose 服务名。

