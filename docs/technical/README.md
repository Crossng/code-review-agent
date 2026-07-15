# 技术文档目录

本目录定义 RepoPilot 的系统架构、模块边界、接口、数据模型和关键工程机制。

## 文件说明

| 文件 | 内容 |
| --- | --- |
| [architecture.md](./architecture.md) | 总体架构、服务边界、核心链路和部署视图 |
| [repository-structure.md](./repository-structure.md) | 代码仓库结构、模块目录和命名约定 |
| [backend-modules.md](./backend-modules.md) | Spring Boot 后端模块拆分、职责和领域模型 |
| [agent-workflow.md](./agent-workflow.md) | LangGraph Agent 编排、状态机、重试和事件 |
| [mcp-tools.md](./mcp-tools.md) | MCP 工具清单、输入输出和安全约束 |
| [api-design.md](./api-design.md) | REST API 设计、请求响应、错误码 |
| [database-design.md](./database-design.md) | PostgreSQL 表结构、状态字段和索引建议 |
| [sandbox-and-github.md](./sandbox-and-github.md) | Docker 沙箱、Git 工作区和 GitHub PR 集成 |
| [frontend-pages.md](./frontend-pages.md) | 前端页面、用户流程、关键状态和组件边界 |

## 技术路线

MVP 采用“Spring Boot 主平台 + Python LangGraph Agent Worker + Spring AI MCP Tool Server + Docker 沙箱 + GitHub API”的混合路线。主平台突出 Java 后端工程能力，Agent Worker 负责长任务编排，MCP Tool Server 负责把仓库分析、Git、Maven、PR 等能力工具化。
