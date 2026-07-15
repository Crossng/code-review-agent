# MVP 范围说明

## 1. MVP 目标

MVP 的目标是用最小但完整的功能集证明 RepoPilot 的核心价值：AI Agent 能够围绕 Java/Spring Boot 仓库完成“理解任务、检索代码、生成补丁、沙箱测试、人工审批、创建 PR”的工程闭环。

## 2. MVP 闭环

```text
用户登录
  -> 添加 GitHub 仓库
  -> clone 仓库
  -> 扫描 Java 项目结构
  -> 解析 AST 与生成代码索引
  -> 创建 Agent 任务
  -> 规划任务
  -> 检索上下文
  -> 生成 unified diff
  -> 沙箱应用 diff
  -> 运行 mvn test
  -> 展示测试结果与 diff
  -> 用户审批
  -> 创建 Pull Request
```

## 3. P0 必做范围

| 编号 | 能力 | 交付物 | 验收标准 |
| --- | --- | --- | --- |
| MVP-001 | 用户登录 | 注册、登录、JWT 鉴权接口 | 未登录不能访问项目和任务接口 |
| MVP-002 | 项目接入 | GitHub repo URL 保存、clone、项目列表 | 接入仓库后能看到 clone 状态和本地路径 |
| MVP-003 | 仓库扫描 | 文件树、Spring Boot 目录识别 | 能识别 `src/main/java`、`src/test/java`、`pom.xml` |
| MVP-004 | AST 索引 | 类、方法、注解、包名、文件路径 | 至少能识别 Controller、Service、Mapper、Entity |
| MVP-005 | 代码检索 | 关键词检索 + 向量检索接口 | 输入任务后返回相关文件和 chunk |
| MVP-006 | Agent 任务 | 创建、启动、取消、查询任务 | 任务状态按状态机流转；取消运行中任务后 task/current run 进入 `CANCELLED`，后台执行不再推进后续节点；同一项目已有其他写入型任务运行时，新启动、重新生成、审批推进或 PR 准备返回 `PROJECT_WRITE_TASK_RUNNING` |
| MVP-007 | 任务规划 | Planner 输出步骤 | UI 或 API 能查看计划 |
| MVP-008 | diff 生成 | unified diff 记录 | diff 可预览，不直接改主工作区；patch 记录包含 `generationMode` 以区分 Spring recipe、LLM Coder draft、RepairAgent 或安全规划回退 |
| MVP-009 | 沙箱测试 | Docker 或隔离工作区运行 `mvn test` | 测试日志保存到 `test_run` |
| MVP-010 | 人工审批 | Approve、Reject、Regenerate | 未审批不能创建 PR |
| MVP-011 | PR 创建 | GitHub 分支、commit、PR | 返回 PR URL 和状态 |
| MVP-012 | 日志追踪 | Agent step、tool call、model call | 任务详情可追溯每一步 |

## 4. P1 加分范围

| 编号 | 能力 | 说明 |
| --- | --- | --- |
| MVP-P1-001 | RepairAgent | 已接入最多 2 次修复循环；当前支持 Maven 测试依赖缺失和常见 Java 标准库缺 import 编译失败，后续扩展到更通用的失败日志修复 |
| MVP-P1-002 | ReviewAgent | 规则化 diff 风险审查已落地；LLM 审查增强后续接入 |
| MVP-P1-003 | WebSocket 日志 | 实时推送 Agent 执行过程 |
| MVP-P1-004 | API 文档生成 | 根据 Controller 扫描输出接口文档 |
| MVP-P1-005 | 调用链分析 | Controller -> Service -> Mapper 调用链 |

## 5. 暂缓范围

- 企业级多租户和复杂 RBAC。
- 自动合并 PR。
- 对 Gradle、Kotlin、Node.js、Python 仓库的正式支持。
- 大型 monorepo 的增量索引优化。
- 生产级安全沙箱加固。
- 多模型管理后台的完整计费与配额。

## 6. MVP 技术取舍

| 决策点 | MVP 选择 | 原因 |
| --- | --- | --- |
| Agent 路线 | Spring Boot 主平台 + Python LangGraph Worker | 更容易快速跑通长任务 Agent 编排 |
| 向量库 | PostgreSQL + pgvector | 减少独立服务数量，便于本地部署 |
| 实时通信 | 先 SSE，后 WebSocket | SSE 更适合单向日志流，开发成本低 |
| Git 操作 | Git CLI 优先，JGit 作为后续封装 | CLI 行为接近真实开发流程，调试直观 |
| 沙箱 | Docker 容器 + 工作区挂载 | 与软件工程 Agent 的执行隔离目标一致 |

## 7. MVP 验收演示

演示任务建议使用一个小型 Spring Boot 示例仓库，执行“新增 User 分页查询接口”、“修复一个参数校验 bug”、“新增 User 数量统计接口”或“新增 User 创建接口”。当前 browser smoke 覆盖这四类任务：分页接口走完整审批与本地 PR 准备，User id 参数校验验证真实 patch 与沙箱测试，User count API 验证第三条 Spring Coder recipe，User create API 验证第四条 Spring Coder recipe。演示必须包含：

1. 仓库接入成功。
2. 项目扫描结果展示。
3. Agent 计划展示。
4. 相关代码检索结果展示。
5. diff 预览。
6. Maven 测试日志。
7. 人工审批按钮。
8. 本地 `DRAFT_READY` PR 分支/commit；启用 GitHub 发布凭据后展示 GitHub PR 链接。
