# 沙箱与 GitHub 集成设计

## 1. 目标

RepoPilot 必须把不可信代码执行限制在受控环境中，并把最终修改纳入 GitHub 标准 PR 流程。沙箱和 GitHub 集成共同保证“可执行、可审查、可追溯”。

## 2. 工作区结构

```text
workspace/
├── repos/
│   └── {projectId}/
│       └── source/              # clone 后的仓库
├── runs/
│   └── {runId}/
│       ├── source/              # 本次 run 的隔离副本
│       ├── patch.diff
│       ├── test.log
│       └── metadata.json
└── tmp/
```

约定：

- `repos/{projectId}/source` 是项目基础工作区。
- `runs/{runId}/source` 是任务执行副本。
- Agent 生成的 diff 先应用到 run 工作区。
- 审批后再同步到 PR 分支。

## 3. 沙箱执行流程

```text
1. 创建 run 工作区
2. 复制或 checkout 指定 commit
3. 写入 patch.diff
4. 校验 diff 路径和格式，拒绝路径穿越、绝对路径、Windows/反斜杠路径、`.git`/secret 目录和二进制 patch
5. docker run 挂载 run 工作区
6. 在容器中执行 `git apply ../patch.diff`
7. 在容器中执行 `mvn -q test`
8. 采集 exit code、stdout、stderr、耗时
9. 写入 `test_run`
10. 清理临时容器
```

## 4. Docker 约束

| 项 | MVP 设置 |
| --- | --- |
| 镜像 | `maven:3.9-eclipse-temurin-17` |
| 网络 | 默认关闭或限制，只在确有依赖下载需求时开放 |
| CPU | 限制容器 CPU |
| 内存 | 限制容器内存 |
| 超时 | `mvn test` 默认 10 分钟 |
| 挂载 | run 工作区、只包含依赖缓存的 Maven local repository |
| 用户 | 尽量使用非 root 用户 |

本地开发默认把工作区 `.m2` 挂载到容器的 `/root/.m2/repository`，避免沙箱测试被 Maven Central 网络波动阻断。生产环境可通过 `REPOPILOT_MAVEN_CACHE` 指向受控依赖缓存。

`GET /api/settings/sandbox` 提供当前 Docker sandbox 配置的只读 readiness 状态，覆盖 Docker daemon、sandbox image、workspace root、Maven cache 和 timeout。该接口不启动测试容器，只用于控制台在长任务前解释测试执行前置条件。

## 5. Git 分支策略

| 分支 | 用途 |
| --- | --- |
| `main` 或默认分支 | PR base |
| `repopilot/task-{taskId}` | Agent 修改分支 |
| `repopilot/task-{taskId}-retry-{n}` | 冲突或重试时可选 |

Commit message 模板：

```text
feat: add user pagination API

由 RepoPilot 生成。

任务：#{taskId}
运行：#{runId}
测试：mvn test 已通过
```

## 6. GitHub PR 流程

```text
1. 校验 patch 状态为 APPROVED
2. 校验 patch 对应的最新 test_run 为 PASSED
3. 校验本地仓库工作区干净
4. 从 base branch 创建 target branch
5. 应用最终 diff
6. git status 检查变更
7. git commit
8. 保存 pull_request_record，状态为 DRAFT_READY
9. git push origin target branch
10. 调用 GitHub API 创建 PR
11. 更新 pull_request_record URL、编号、状态和打开时间
```

GitHub 发布配置：

```text
REPOPILOT_GITHUB_ENABLED=true
REPOPILOT_GITHUB_TOKEN=<github-token>
REPOPILOT_GITHUB_API_BASE_URL=https://api.github.com
```

本地开发默认 `REPOPILOT_GITHUB_ENABLED=false`，因此只生成本地分支、commit 和 `DRAFT_READY` 记录，不推送远端。

真实远端 PR 演示前可运行 `./scripts/real-token-demo-check.sh` 做只读体检；默认模式会提示 Docker、PostgreSQL/Redis、沙箱镜像、Maven cache、后端真实 Coder、Worker Planner/Coder 可选模型入口、`REPOPILOT_GITHUB_ENABLED=true` 和 `REPOPILOT_GITHUB_TOKEN`/`GITHUB_TOKEN` 是否到位但不失败，`--strict` 模式会在 Docker、后端真实 Coder 或远端 PR 缺项时返回非 0；Worker Planner/Coder 默认关闭只作为可选增强提示，显式启用但缺少 fixture/model/key 时才在 strict 模式失败。需要顺手启动基础依赖时可加 `--start-deps`。脚本只展示 token/key 是否配置，不打印 token、模型 key 或 Authorization header。

真实 GitHub PR 发布演示可运行 `./scripts/real-github-pr-demo.sh`。该脚本要求显式设置 `REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr`、`REPOPILOT_REAL_GITHUB_PR_REPO_URL`、`REPOPILOT_GITHUB_ENABLED=true` 和 GitHub token，然后创建临时用户和指定 GitHub 项目，生成稳定的 User count API patch，自动审批，通过 PR preflight 后真实 push `repopilot/task-{taskId}` 分支并创建远端 PR。脚本会清理 RepoPilot 本地临时业务数据，但不会关闭远端 PR 或删除远端分支；演示时应使用可丢弃的公开仓库，且仓库内容应与 `examples/demo-spring-repo` 结构一致。当前 clone 阶段不注入 token，私有仓库需要本机 Git 已有读取凭据。

`GET /api/settings/github` 提供当前 GitHub 发布配置的只读脱敏状态，前端可展示 `LOCAL_DRAFT_ONLY` 或 `REMOTE_GITHUB_PR`、API base URL、token 是否配置和缺失项，但不返回 token 原文。

`GET /api/tasks/{id}/pull-request/preflight` 提供任务级 PR 发布前置检查，覆盖任务状态、patch 审批、沙箱测试、本地草稿准备、远程 GitHub 仓库资格和 token 配置。该接口只读，不创建分支或 PR，供控制台在审批前后展示 blocker 和 warning。

远端发布链路的本地验证使用临时 bare Git 仓库模拟 `origin`，并用本地 HTTP server 模拟 GitHub `/repos/{owner}/{repo}/pulls` API。测试会真实执行 `git push origin {targetBranch}`，断言远端分支存在、PR API 收到脱敏 token header、title/head/base/body 正确，并确认服务返回 PR number/url。这样即使没有真实 GitHub token，也可以重复验证 push + GitHub API 创建 PR 的主路径。

PR body 模板：

```markdown
## 摘要

- 由 RepoPilot 为任务 #{taskId} 生成
- {patch summary}

## 验证

- mvn test: {PASSED/FAILED}
- 测试运行 ID: {testRunId}

## Agent 追踪

- Plan: {plan summary}
- Retrieved files: {file list}
- Review report: {risk summary}
```

## 7. 失败处理

| 阶段 | 失败处理 |
| --- | --- |
| clone 失败 | 标记项目或任务失败，记录 GitHub 错误 |
| diff 应用失败 | 标记 patch 失败，要求重新生成 |
| 测试失败 | 保存日志，最多进入 RepairAgent 2 次；当前确定性修复覆盖缺失 `spring-boot-starter-test` 和常见 Java 标准库缺 import 编译失败，无法修复时进入 `FAILED_TEST` 等待人工处理或 Regenerate |
| push 失败 | 保留本地分支，允许重试 |
| PR 创建失败 | 保存 GitHub API 响应，允许从 `FAILED_PR_CREATION` 重试；重试会复用已有 `pull_request_record` 和本地分支/commit，成功后更新为 `OPEN` |

## 8. 安全要求

- 禁止把宿主根目录挂载进容器。
- 禁止工具读取项目工作区以外路径。
- GitHub token 只在需要 push 和创建 PR 时使用。
- 日志中必须脱敏 token、Authorization header、模型 key。
- PR 创建必须校验审批记录和测试记录。
