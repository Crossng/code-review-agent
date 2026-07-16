# Scripts

后续放本地开发、索引、演示、清理脚本。MVP 阶段优先保持脚本短小、可读、可重复执行。

## Agent Worker Smoke

```bash
./scripts/agent-worker-smoke.sh
./scripts/agent-worker-callback-smoke.sh
./scripts/agent-worker-tool-smoke.sh
./scripts/agent-worker-node-smoke.sh
```

`agent-worker-smoke.sh` 用于验证 Python Agent Worker 的最小服务契约。它会：

- 检查 `fastapi`、`uvicorn`、`pydantic` 和 `pydantic_settings` 依赖是否可导入。
- 若 `REPOPILOT_AGENT_WORKER_URL` 已有 worker，则复用；否则临时启动 `agent-worker/app/main.py`。
- 验证 `GET /health` 返回 `status=UP` 和 `service=agent-worker`。
- 验证 `POST /runs/303/start` 返回 `accepted=true`、`status=QUEUED` 和 MVP graph node 清单。
- 将证据写入 `output/agent-worker-smoke/last-run.json`。

`agent-worker-callback-smoke.sh` 用于验证 Python Agent Worker 的后端回写 client。它会：

- 启动本地 HTTP stub。
- 调用 `BackendApiClient.record_step(...)`。
- 调用 `BackendApiClient.record_tool_call(...)`。
- 调用 `BackendApiClient.record_model_call(...)`。
- 调用 `BackendApiClient.record_patch(...)`。
- 调用 `BackendApiClient.update_status(...)`。
- 验证 `/api/internal/agent-worker/runs/{run_id}/steps`、`/tool-calls`、`/model-calls`、`/patches` 与 `/status` 路径、`X-RepoPilot-Worker-Token` header、step/tool/model/patch/status JSON。
- 将证据写入 `output/agent-worker-callback-smoke/last-run.json`。

`agent-worker-tool-smoke.sh` 用于验证 Python Agent Worker 的后端工具读取 client。它会：

- 启动本地 HTTP stub。
- 调用 `BackendApiClient.load_run_context(...)`、`list_project_files(...)`、`read_project_file(...)`、`search_code(...)` 和 `list_symbols(...)`。
- 验证 `/api/internal/agent-worker/runs/{run_id}/context`、`/project/files`、`/project/file`、`/project/search` 与 `/project/symbols` 路径、`X-RepoPilot-Worker-Token` header 和 query 参数。
- 将证据写入 `output/agent-worker-tool-smoke/last-run.json`。

`agent-worker-node-smoke.sh` 用于验证 Python Agent Worker 的初始、检索、补丁草稿、安全预检、沙箱测试与风险审查节点执行。它会：

- 启动本地后端 HTTP stub 和真实 FastAPI worker。
- 使用 callback token 调用 `POST /runs/{run_id}/start`。
- 验证 Worker 后台执行 `load_task_context`、`ensure_index`、确定性 `plan_task`、`retrieve_context` 和 `generate_patch`。
- 验证 Worker 拉取 context、files、symbols、search、file，自动回写 tool call audit，回写 `generate_patch` model call audit 和 `WORKER_SAFE_PLANNING_DRAFT` patch draft，并回写五个 SUCCESS step。
- 验证 Worker 在 patch draft 持久化后调用 `/patches/{patchId}/safety` 触发后端 diff 安全预检。
- 验证 Worker 在安全预检通过后调用 `/patches/{patchId}/sandbox-tests` 触发后端沙箱应用和 Maven 测试。
- 验证 Worker 在沙箱测试通过后调用 `/patches/{patchId}/review` 触发后端规则化风险审查。
- 将证据写入 `output/agent-worker-node-smoke/last-run.json`。

## Real Token Demo Check

```bash
./scripts/real-token-demo-check.sh
```

该脚本用于真实 token 演示前做环境体检，默认只提示缺项，不因为没有模型 key 或 GitHub token 失败。它会检查：

- 项目关键文件、`git`、`java`、`mvn`、`npm`、`docker` 和 Docker Compose。
- Docker daemon、PostgreSQL/Redis Compose 状态、沙箱镜像、超时和 Maven cache。
- 真实 Coder 所需的 `REPOPILOT_CODER_MODE=openai-compatible`、`REPOPILOT_CODER_API_KEY`/`OPENAI_API_KEY` 和 `REPOPILOT_CODER_MODEL`。
- 远端 GitHub PR 所需的 `REPOPILOT_GITHUB_ENABLED=true` 和 `REPOPILOT_GITHUB_TOKEN`/`GITHUB_TOKEN`。
- 后端和前端端口是否已有进程监听。

脚本只展示密钥是否配置，不打印 GitHub token、模型 key 或 Authorization header。正式演示前可使用严格模式：

```bash
./scripts/real-token-demo-check.sh --strict
```

如需先启动 PostgreSQL 和 Redis：

```bash
./scripts/real-token-demo-check.sh --start-deps
```

## Real Coder Demo

```bash
./scripts/real-coder-demo.sh
```

该脚本用于有真实模型 token 时跑一条 API 级端到端演示。它会：

- 启动 PostgreSQL 和 Redis。
- 若 `REPOPILOT_BACKEND_URL` 对应的后端未运行，则用当前 shell 的真实 Coder 环境变量临时启动后端。
- 注册临时用户，创建本地 `examples/demo-spring-repo` 项目，执行 clone 和 index。
- 创建一个不会命中本地 recipe 的小任务，要求模型只新增 `.repopilot/real-coder-demo-note.md`。
- 启动 Agent run，并等待任务进入 `WAITING_HUMAN_APPROVAL`。
- 验证 `generate_patch`、`validate_patch_safety`、`run_tests`、`review_patch` 成功。
- 验证 patch 为 `generationMode=LLM_CODER_DRAFT`、`generationProvider=OPENAI_COMPATIBLE`，且沙箱 `mvn -q test` 通过。
- 将脱敏后的运行证据写入 `output/real-coder-demo/last-run.json`。
- 清理本次演示创建的临时用户、项目、任务、运行、补丁、测试、审批、PR 数据和脚本自启后端的 workspace。

如果 8080 没有后端进程，运行前至少需要：

```bash
export REPOPILOT_CODER_MODE=openai-compatible
export REPOPILOT_CODER_API_KEY=...
export REPOPILOT_CODER_MODEL=...
./scripts/real-coder-demo.sh
```

如果已有后端在运行，脚本会先通过 `GET /api/settings/coder` 检查后端实际 Coder 配置；若不是 `OPENAI_COMPATIBLE` 且 ready，则直接失败并提示重启后端。脚本不会打印模型 key、GitHub token 或 Authorization header。

## Real GitHub PR Demo

```bash
./scripts/real-github-pr-demo.sh
```

该脚本用于真实 GitHub token 环境下跑远端 PR 发布演示。它会真实 push 分支并创建 PR，因此默认需要显式确认：

```bash
export REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr
export REPOPILOT_REAL_GITHUB_PR_REPO_URL=https://github.com/<owner>/<demo-repo>.git
export REPOPILOT_GITHUB_ENABLED=true
export REPOPILOT_GITHUB_TOKEN=...
./scripts/real-github-pr-demo.sh
```

该脚本会：

- 启动 PostgreSQL 和 Redis。
- 若 `REPOPILOT_BACKEND_URL` 对应的后端未运行，则用当前 shell 的 GitHub 发布环境变量临时启动后端。
- 注册临时用户，创建指定 GitHub 项目，执行 clone 和 index。
- 创建默认任务“新增 User count API”，使用本地 recipe 生成稳定 patch 并通过 Docker 沙箱测试。
- 自动审批已测试通过的 patch，检查 PR preflight，然后调用 `/api/tasks/{taskId}/pull-request`。
- 验证 PR 记录为 `OPEN`，包含 PR number、URL、target branch、commit sha、`remotePushedAt` 和 `openedAt`。
- 将脱敏后的运行证据写入 `output/real-github-pr-demo/last-run.json`。
- 清理本次演示创建的临时用户、项目、任务、运行、补丁、测试、审批和本地 PR 记录。

请使用可丢弃的公开演示仓库，仓库内容应与 `examples/demo-spring-repo` 结构一致。当前 clone 阶段不注入 token；私有仓库需要本机 Git 已有读取凭据。远端 PR、远端分支不会由脚本自动关闭或删除，便于演示和留存证据。脚本不会打印 GitHub token、模型 key 或 Authorization header。

## Browser Smoke

```bash
./scripts/browser-smoke.sh
```

该脚本会：

- 启动 PostgreSQL 和 Redis。
- 若本地 8080/5173 未运行，则临时启动后端和前端。
- 安装/确认 Playwright Chromium。
- 验证演示就绪总览：本地闭环可演示、真实模型为可选增强、远端 GitHub PR 处于本地草稿模式，并展示真实 token 演示所需环境变量名。
- 在真实浏览器中注册 smoke 用户、创建本地 demo 项目、Clone、Index、刷新代码地图、验证 Controller API 风险筛选并搜索 `UserService`。
- 创建 Agent 任务，运行 Agent，验证步骤、模型调用、工具调用、任务 SSE 快照流、真实 `GET /api/users/page` patch、文件级 `changedFiles` 摘要、自动风险审查、Maven 沙箱测试，点击 Regenerate 并校验新 Run/Patch/Test run 编号，再完成人工审批和本地 `DRAFT_READY` PR 准备记录。
- 创建第二个 User id 参数校验任务，验证真实 guard patch、`UserServiceTest` 覆盖、文件级 `changedFiles` 摘要、自动风险审查和 Maven 沙箱测试。
- 将截图写入 `output/playwright/repopilot-browser-smoke.png`。
- 清理本次 smoke 创建的用户、项目、任务、运行、补丁、测试、审批、PR 数据和专用 workspace。
