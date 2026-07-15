# RepoPilot 文档中心

本文档区以 [development-plan.md](./development-plan.md) 为开发基准，把原始项目策划拆成可执行的需求文档、技术文档和项目管理文档。后续编码、排期、验收和简历展示都以这里的文档为准。

## 文档分区

| 目录 | 用途 | 主要读者 |
| --- | --- | --- |
| `requirements/` | 产品需求、MVP 范围、用户故事和验收口径 | 产品、开发、面试讲解 |
| `technical/` | 架构、后端模块、Agent 工作流、MCP 工具、API、数据库、沙箱与 GitHub 集成 | 开发、测试、部署 |
| `management/` | 路线图、里程碑、验收清单 | 项目推进、复盘 |
| `assets/` | 后续放架构图、流程图、演示截图等素材 | 文档维护 |

## 推荐阅读顺序

1. [项目开发基准](./development-plan.md)
2. [产品需求文档](./requirements/product-requirements.md)
3. [MVP 范围说明](./requirements/mvp-scope.md)
4. [总体技术架构](./technical/architecture.md)
5. [后端模块设计](./technical/backend-modules.md)
6. [仓库结构规范](./technical/repository-structure.md)
7. [Agent 工作流设计](./technical/agent-workflow.md)
8. [API 设计](./technical/api-design.md)
9. [数据库设计](./technical/database-design.md)
10. [里程碑路线图](./management/roadmap.md)
11. [验收清单](./management/acceptance-checklist.md)

## 文档维护规则

- `development-plan.md` 保留为原始规划基准，不直接改写。
- 需求变化先更新 `requirements/`，再同步影响到 `technical/` 和 `management/`。
- 每个技术模块的实现必须能回溯到一个需求编号或里程碑任务。
- MVP 阶段优先保证闭环：接入仓库、索引代码、创建任务、生成 diff、沙箱测试、人工审批、创建 PR。
