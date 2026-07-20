# 真实 Token 演示操作手册

本文用于正式演示 RepoPilot 的三条路线：本地闭环、真实 Coder 模型、真实 GitHub PR 发布。所有命令都默认在仓库根目录执行。

## 1. 演示路线

| 路线 | 命令 | 适用场景 |
| --- | --- | --- |
| 本地闭环 | `./scripts/browser-smoke.sh` | 无真实 token 时验证项目接入、任务、补丁、沙箱、审批和本地 PR 草稿 |
| 真实 Coder | `./scripts/real-coder-demo.sh` | 有 OpenAI-compatible 模型 key 时验证真实模型生成 diff 到人工审批暂停点 |
| 真实 GitHub PR | `./scripts/real-github-pr-demo.sh` | 有 GitHub token 和可丢弃 demo 仓库时验证远端 push 与 PR 创建 |
| Worker Coder 业务闭环 | `./scripts/agent-worker-business-smoke.sh` | 无真实 token 时验证 Python Worker 主链路、双业务 diff、retry audit 和本地 PR 草稿 |

## 2. 演示前体检

先运行默认体检，确认本机命令、Docker、PostgreSQL/Redis、沙箱、真实 Coder、Worker 模型和 GitHub PR 前置项：

```bash
./scripts/real-token-demo-check.sh
```

脚本会生成两份脱敏证据：

- `output/real-token-demo-check/last-run.json`
- `output/real-token-demo-check/last-run.md`

正式演示前使用 strict 模式。strict 会在 Docker、真实 Coder 或远端 PR 关键项缺失时返回非 0，但仍会先写出 JSON 和 Markdown 证据，便于定位缺口。

```bash
./scripts/real-token-demo-check.sh --strict
```

需要顺手启动 PostgreSQL 和 Redis 时：

```bash
./scripts/real-token-demo-check.sh --start-deps
```

## 3. 真实 Coder 演示

如果后端尚未运行，脚本会使用当前 shell 的真实 Coder 环境变量启动后端：

```bash
export REPOPILOT_CODER_MODE=openai-compatible
export REPOPILOT_CODER_API_KEY=...
export REPOPILOT_CODER_MODEL=...

./scripts/real-token-demo-check.sh --strict
./scripts/real-coder-demo.sh
```

成功后查看：

- `output/real-coder-demo/last-run.json`
- 任务状态应进入 `WAITING_HUMAN_APPROVAL`
- patch 应为 `LLM_CODER_DRAFT / OPENAI_COMPATIBLE`
- 沙箱测试应为 `PASSED`

## 4. 真实 GitHub PR 演示

远端 PR 演示会真实 push 分支并创建 PR。请只使用可丢弃的公开演示仓库，且仓库结构应与 `examples/demo-spring-repo` 一致。

```bash
export REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr
export REPOPILOT_REAL_GITHUB_PR_REPO_URL=https://github.com/<owner>/<demo-repo>.git
export REPOPILOT_GITHUB_ENABLED=true
export REPOPILOT_GITHUB_TOKEN=...

./scripts/real-token-demo-check.sh --strict
./scripts/real-github-pr-demo.sh
```

成功后查看：

- `output/real-github-pr-demo/last-run.json`
- PR 记录状态应为 `OPEN`
- 证据中应包含 PR number、URL、target branch、commit sha、`remotePushedAt` 和 `openedAt`

脚本会清理 RepoPilot 本地临时数据，但不会关闭远端 PR 或删除远端分支，方便演示留档。演示结束后由操作者手动关闭 PR、删除远端分支。

## 5. 安全约束

- 体检和演示证据只记录 key/token 是否配置，不记录密钥明文。
- 不打印 Authorization header。
- 不把真实 token 写入 `.env`、文档或提交。
- 远端 PR 脚本必须显式设置 `REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr`。
- 私有 GitHub 仓库的 clone 阶段不注入 token，需要本机 Git 已具备读取凭据。

## 6. 常见失败

| 现象 | 处理 |
| --- | --- |
| Docker daemon 不可访问 | 启动 Docker Desktop，再运行 `./scripts/real-token-demo-check.sh --start-deps` |
| strict 提示真实模型未就绪 | 补齐 `REPOPILOT_CODER_MODE=openai-compatible`、模型 key 和 `REPOPILOT_CODER_MODEL` |
| strict 提示远端 PR 未就绪 | 补齐确认开关、GitHub demo repo URL、`REPOPILOT_GITHUB_ENABLED=true` 和 GitHub token |
| `real-github-pr-demo.sh` clone 失败 | 确认 demo 仓库存在、默认分支正确，本机 Git 对该仓库有读取权限 |
| PR 创建失败但本地分支存在 | 查看 `output/real-github-pr-demo/last-run.json` 和后端日志；修复 token/repo 权限后可重新演示 |
