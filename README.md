# RepoPilot

RepoPilot 是一个面向 Java/Spring Boot 仓库的 AI 软件工程 Agent 平台。它的目标是接入 GitHub 仓库，理解项目结构，分析自然语言任务，生成代码 diff，在沙箱中运行测试，并在人工审批后创建 Pull Request。

当前仓库处于规划和项目骨架搭建阶段，文档以 [docs/development-plan.md](./docs/development-plan.md) 为原始开发基准。

## 文档入口

- [文档中心](./docs/README.md)
- [产品需求文档](./docs/requirements/product-requirements.md)
- [MVP 范围说明](./docs/requirements/mvp-scope.md)
- [总体技术架构](./docs/technical/architecture.md)
- [仓库结构规范](./docs/technical/repository-structure.md)
- [开发路线图](./docs/management/roadmap.md)
- [验收清单](./docs/management/acceptance-checklist.md)

## MVP 闭环

```text
GitHub 仓库接入
  -> Java/Spring Boot 结构扫描
  -> AST 与代码索引
  -> Agent 任务规划
  -> 相关代码检索
  -> 生成 unified diff
  -> Docker 沙箱测试
  -> 人工审批
  -> GitHub Pull Request
```

## 技术路线

- Spring Boot 3：主平台、鉴权、项目、任务、审批、PR、日志。
- Python + LangGraph：Agent Worker 和长任务编排。
- Spring AI MCP Server：工具能力暴露。
- PostgreSQL + pgvector：业务数据和代码向量检索。
- Redis：任务锁、短期状态和事件缓存。
- Docker：沙箱执行 Maven 编译和测试。

## 当前实现进度

第一批可运行骨架已经落地：

- `docker-compose.yml`：PostgreSQL + pgvector、Redis。
- `backend/`：Spring Boot、Flyway、JPA、JWT 鉴权、项目 API、Agent 任务 API。
- `backend/` now includes Git clone workspace management and JavaParser AST symbol indexing.
- `agent-worker/`：FastAPI Worker 契约和 MVP graph node 清单。
- `frontend/`：Vite React 控制台第一屏。

## 本地启动

启动基础依赖：

```bash
docker compose up -d postgres redis
```

运行后端：

```bash
cd backend
mvn -Dmaven.repo.local=../.m2 spring-boot:run
```

运行前端：

```bash
cd frontend
npm install
npm run dev
```

后端健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

## 已验证

- `docker compose config`
- `docker compose up -d postgres redis`
- `backend`: `mvn -q -Dmaven.repo.local=../.m2 test`
- `frontend`: `npm run build`
- `agent-worker`: Python 语法编译检查
- 后端真实 API 流：注册、登录、创建项目、创建 Agent 任务、启动 run、查询 step
