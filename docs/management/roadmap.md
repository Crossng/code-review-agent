# 开发路线图

## 1. 总体节奏

| 阶段 | 时间 | 目标 |
| --- | --- | --- |
| Phase 0 | 第 0 周 | 文档、架构、仓库骨架 |
| Phase 1 | 第 1-2 周 | Spring Boot 基础平台和项目接入 |
| Phase 2 | 第 3-4 周 | 代码扫描、AST 索引、检索 |
| Phase 3 | 第 5-6 周 | Agent Worker、diff 生成、沙箱测试 |
| Phase 4 | 第 7-8 周 | 审批、PR 创建、日志审计、Demo 闭环 |
| Phase 5 | 第 9-12 周 | ReviewAgent、调用链、API 文档、多模型配置等进阶功能 |

## 2. Phase 0：文档与骨架

交付物：

- 文档分区。
- PRD、MVP 范围、用户故事。
- 技术架构、API、数据库、Agent、MCP、沙箱设计。
- 后续代码仓库结构。

完成标准：

- 文档能支撑直接创建 backend、agent-worker、frontend、docker-compose。

## 3. Phase 1：后端基础平台

任务：

- 创建 Spring Boot 3 项目。
- 接入 PostgreSQL、Redis。
- 实现用户注册登录和 JWT。
- 实现项目表、项目 API。
- 实现 GitHub repo URL 接入和 clone。
- 建立统一响应、异常、审计字段。

交付物：

- `backend/` 项目可启动。
- `POST /api/auth/login` 可用。
- `POST /api/projects` 可 clone 仓库。

## 4. Phase 2：代码扫描与索引

任务：

- 实现文件树扫描。
- 引入 JavaParser。
- 抽取 Controller、Service、Mapper、Entity。
- 生成 `code_file`、`code_symbol`、`code_chunk`。
- 接入 embedding 模型和 pgvector。
- 实现 `search_code`。

交付物：

- 项目详情页或 API 能查看 Java 符号。
- 输入自然语言能检索相关代码 chunk。

## 5. Phase 3：Agent 与沙箱

任务：

- 创建 `agent-worker/`。
- 使用 LangGraph 实现状态机。
- 实现 Planner、Retriever、Coder、Tester 节点。
- 创建 MCP Tool Server 或内部工具 API。
- 实现 diff 校验、应用和 `mvn test`。
- 保存 Agent step、tool call、test run。

交付物：

- `POST /api/agent/tasks/{id}/run` 可触发任务。
- Agent 能生成 diff 并运行测试。

## 6. Phase 4：审批与 PR 闭环

任务：

- 实现 diff 预览。
- 实现 Approve、Reject、Regenerate。
- 实现 GitHub 分支、commit、push、PR 创建。
- 实现 SSE 日志流。
- 整理 Demo 仓库和演示脚本。

交付物：

- 从任务创建到 GitHub PR 的完整演示可跑通。
- 任务详情能看到计划、检索、diff、测试、审批、PR。

## 7. Phase 5：进阶能力

候选任务：

- ReviewAgent 风险审查。
- Controller API 文档生成。
- Controller -> Service -> Mapper 调用链分析。
- 权限与参数校验风险检查。
- 多模型配置。
- 更完整的 WebSocket 实时日志。
- Java 原生 Agent 分支或模块。

## 8. 每周节奏

- 周初明确本周交付物。
- 每完成一个模块补充对应 README 或接口说明。
- 每周至少保留一个可运行 Demo 状态。
- 不把未验收的实验功能混入 MVP 主链路。

