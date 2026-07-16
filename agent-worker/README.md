# RepoPilot Agent Worker

该模块承载 RepoPilot 长任务的 Python Agent Worker 和 LangGraph 图执行入口。

Current slice:

- `GET /health` returns worker health and the active graph execution engine.
- `POST /runs/{run_id}/start` accepts a run contract and returns the planned MVP graph nodes.
- `plan_task` supports optional auditable structured Worker Planner advice while keeping the deterministic plan as the safe default.
- `generate_patch` supports optional Worker Coder raw diff parsing while keeping the safe planning draft as the default.
- `../scripts/agent-worker-smoke.sh` starts or reuses the worker and verifies both contracts.

## Local Contract Smoke

```bash
../scripts/agent-worker-smoke.sh
../scripts/agent-worker-callback-smoke.sh
../scripts/agent-worker-tool-smoke.sh
../scripts/agent-worker-node-smoke.sh
../scripts/agent-worker-planner-smoke.sh
../scripts/agent-worker-coder-smoke.sh
../scripts/agent-worker-coder-model-smoke.sh
```

The contract smoke script checks:

- FastAPI dependencies are importable.
- `/health` returns `status=UP`, `service=agent-worker` and `graph_engine=LANGGRAPH` or `SEQUENTIAL_FALLBACK`.
- `/runs/303/start` returns `accepted=true`, `status=QUEUED` and the expected MVP graph node list.
- Evidence is written to `output/agent-worker-smoke/last-run.json`.

The callback smoke script checks:

- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/steps`.
- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/tool-calls`.
- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/model-calls`.
- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/patches`.
- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/status`.
- `X-RepoPilot-Worker-Token` is attached.
- Step, tool call, model call, patch and status payloads use the backend callback contract.
- Evidence is written to `output/agent-worker-callback-smoke/last-run.json`.

The tool smoke script checks:

- `BackendApiClient` reads run context from `/api/internal/agent-worker/runs/{run_id}/context`.
- `BackendApiClient` reads repository files, file content, code search results and symbols through run-scoped internal tool endpoints.
- `X-RepoPilot-Worker-Token` is attached to all tool requests.
- Evidence is written to `output/agent-worker-tool-smoke/last-run.json`.

The node smoke script checks:

- A real FastAPI worker starts with a local backend stub.
- `POST /runs/{run_id}/start` schedules initial worker nodes when `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` is configured.
- The worker reads run context/files/symbols/search/file through the backend tool bridge.
- Every run-scoped tool read is automatically recorded through `/tool-calls` with a bounded output summary.
- The worker records `load_task_context`, `ensure_index`, `plan_task`, `retrieve_context` and `generate_patch` SUCCESS steps.
- `plan_task` can attach a fixture-backed model planning note and record a `plan_task` model call audit when `REPOPILOT_WORKER_MODEL_MODE=fixture`.
- The worker records a deterministic `generate_patch` model call and a `WORKER_SAFE_PLANNING_DRAFT` patch draft.
- The worker calls `/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/safety` after the draft is persisted.
- The worker calls `/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/sandbox-tests` when safety passes.
- The worker calls `/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/review` when sandbox tests pass.
- The worker calls `/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/approval-ready` when review passes.
- Evidence is written to `output/agent-worker-node-smoke/last-run.json`.

The planner smoke script checks:

- A real FastAPI worker starts with a local backend stub and a local OpenAI-compatible Chat Completions stub.
- `REPOPILOT_WORKER_MODEL_MODE=openai-compatible` makes `plan_task` call `/v1/chat/completions`.
- The Planner request carries Authorization, model, optional OpenAI organization/project headers, Planner prompt, task/index/search/plan context and `max_completion_tokens`.
- The `plan_task` step output contains `modelPlanText`, structured `modelPlan`, `modelProvider=OPENAI_COMPATIBLE` and the model name returned by the stub.
- `retrieve_context` safely includes selected model-proposed `modelPlan.searchQueries` after the deterministic search queries.
- The worker records two model call audits in the run: `plan_task / OPENAI_COMPATIBLE` and `generate_patch / AGENT_WORKER`.
- The Planner API key is not written into model call prompt/response audit payloads.
- Evidence is written to `output/agent-worker-planner-smoke/last-run.json`.

`agent-worker-coder-smoke.sh` verifies the Worker Coder patch node without a real model token:

- `generate_patch` consumes loaded context, index signals, plan output and retrieved context.
- A fixture Coder model returns one raw unified diff.
- The worker parses the diff, records `generationMode=LLM_CODER_DRAFT`, persists the patch draft and still calls safety, sandbox, review and approval-ready gates.
- Evidence is written to `output/agent-worker-coder-smoke/last-run.json`.

`agent-worker-coder-model-smoke.sh` verifies the full Worker Coder OpenAI-compatible path:

- A real FastAPI worker starts with a local backend stub and a local Chat Completions-compatible Coder stub.
- `REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible` makes `generate_patch` call `/v1/chat/completions`.
- The Coder request carries Authorization, model, optional OpenAI organization/project headers, diff-only Coder prompt, plan/retrieval context and `max_completion_tokens`.
- The worker parses the assistant raw diff into `LLM_CODER_DRAFT`, records `generate_patch / OPENAI_COMPATIBLE` model call audit and continues through safety, sandbox, review and approval-ready gates.
- The Coder API key is not written into model call prompt/response audit payloads.
- Evidence is written to `output/agent-worker-coder-model-smoke/last-run.json`.

## Backend Start Bridge

The Spring Boot backend can shadow-dispatch a run start contract to this worker when configured:

```yaml
repopilot:
  agent-worker:
    enabled: true
    base-url: http://127.0.0.1:8090
```

When enabled, backend run execution records an `agent_worker_start` step with the worker response. If the worker call fails, the failure is recorded as step evidence and the current Spring Boot executor continues as the local fallback.

## Backend Step Callback

Worker nodes can write step evidence and task/run status back to the backend through `BackendApiClient`:

```python
from app.clients.backend_api import BackendApiClient
from app.schemas import AgentStatusUpdateRequest, AgentStepRecordRequest

BackendApiClient().record_step(
    run_id=303,
    step=AgentStepRecordRequest(
        step_name="plan_task",
        status="SUCCESS",
        output={"summary": "Worker generated a plan"},
    ),
)

BackendApiClient().update_status(
    run_id=303,
    status=AgentStatusUpdateRequest(
        task_status="WAITING_HUMAN_APPROVAL",
        run_status="SUCCESS",
        complete_stream=True,
    ),
)
```

The backend requires `X-RepoPilot-Worker-Token`, configured by `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN`.

## Backend Audit Callback

Worker nodes can also write tool call and model call audit records through `BackendApiClient`:

```python
from app.clients.backend_api import BackendApiClient
from app.schemas import AgentModelCallRecordRequest, AgentPatchRecordRequest, AgentToolCallRecordRequest

client = BackendApiClient()

client.record_tool_call(
    run_id=303,
    tool_call=AgentToolCallRecordRequest(
        tool_name="read_project_file",
        status="SUCCESS",
        input={"path": "src/main/java/com/example/demo/user/UserController.java"},
        output={"size": 2048},
        duration_ms=12,
    ),
)

client.record_model_call(
    run_id=303,
    model_call=AgentModelCallRecordRequest(
        step_name="generate_patch",
        model_provider="OPENAI_COMPATIBLE",
        model_name="gpt-test-coder",
        status="SUCCESS",
        prompt={"instruction": "Generate a safe patch"},
        response={"summary": "Generated draft patch"},
        prompt_tokens=12,
        completion_tokens=8,
        total_tokens=20,
    ),
)
```

The Spring Boot backend stores these records in the existing tool/model call audit tables and applies the same sensitive-field redaction used by the local executor.

## Worker 模型计划入口

`plan_task` 已接入 `WorkerModelClient`。默认配置保持保守：只生成确定性计划，不调用模型，也不会多写 model call audit。需要验证模型路径时，可以使用 fixture 模式；需要真实 Planner 摘要时，可以切到 OpenAI-compatible Chat Completions 接口：

```bash
export REPOPILOT_WORKER_MODEL_MODE=fixture
export REPOPILOT_WORKER_MODEL_PROVIDER=WORKER_FIXTURE
export REPOPILOT_WORKER_MODEL_NAME=worker-fixture-plan-v1
export REPOPILOT_WORKER_MODEL_FIXTURE_RESPONSE="优先定位 Controller、Service 和测试，再生成最小 diff。"

export REPOPILOT_WORKER_MODEL_MODE=openai-compatible
export REPOPILOT_WORKER_MODEL_API_BASE_URL=https://api.openai.com/v1
export REPOPILOT_WORKER_MODEL_API_KEY=...
export REPOPILOT_WORKER_MODEL_NAME=gpt-worker-planner
```

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `REPOPILOT_WORKER_MODEL_MODE` | `disabled` | `disabled` 不调用模型；`fixture` 使用固定响应；`openai`/`openai-compatible` 调用 Chat Completions 兼容接口 |
| `REPOPILOT_WORKER_MODEL_PROVIDER` | `WORKER_FIXTURE` | 写入 model call audit 的 provider |
| `REPOPILOT_WORKER_MODEL_NAME` | `worker-fixture-plan-v1` | 写入 model call audit 的模型名 |
| `REPOPILOT_WORKER_MODEL_FIXTURE_RESPONSE` | 空 | fixture 模式下作为 `modelPlanText` 写入 `plan_task` step output |
| `REPOPILOT_WORKER_MODEL_API_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible API base URL |
| `REPOPILOT_WORKER_MODEL_API_KEY` / `OPENAI_API_KEY` | 空 | OpenAI-compatible 模式的 API key；不会写入 prompt/response 审计 |
| `REPOPILOT_WORKER_MODEL_TIMEOUT_SECONDS` | `120` | 模型 HTTP 请求超时 |
| `REPOPILOT_WORKER_MODEL_MAX_COMPLETION_TOKENS` | `1200` | 写入 Chat Completions 请求的 `max_completion_tokens` |
| `REPOPILOT_WORKER_MODEL_INSTRUCTION_ROLE` | `developer` | Planner 指令消息角色，只支持 `developer` 或 `system` |

模型模式会增强 `plan_task` 的结构化计划建议。JSON 响应会被解析为 `modelPlan.summary`、`steps`、`searchQueries`、`risks` 和 `testStrategy`；纯文本响应仍会作为 `modelPlanText` 兼容处理。`retrieve_context` 会在保留确定性 query 的前提下吸收最多两条 `modelPlan.searchQueries`，但模型建议不直接生成代码、不绕过 `generate_patch`、安全预检、沙箱测试、风险审查或人工审批。

## Worker Coder 模型补丁入口

`generate_patch` 默认仍生成 `.repopilot/` 下的 `WORKER_SAFE_PLANNING_DRAFT` 规划草稿。需要验证 Worker 侧模型生成 diff 时，可以单独开启 Worker Coder 模式；它不会复用 Planner 的 `REPOPILOT_WORKER_MODEL_*` 配置，避免计划模型和代码模型互相影响：

```bash
export REPOPILOT_WORKER_CODER_MODEL_MODE=fixture
export REPOPILOT_WORKER_CODER_MODEL_PROVIDER=WORKER_CODER_FIXTURE
export REPOPILOT_WORKER_CODER_MODEL_NAME=worker-fixture-coder-v1
export REPOPILOT_WORKER_CODER_MODEL_FIXTURE_RESPONSE='diff --git a/.repopilot/demo.md b/.repopilot/demo.md
new file mode 100644
--- /dev/null
+++ b/.repopilot/demo.md
@@ -0,0 +1 @@
+Worker Coder fixture'

export REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible
export REPOPILOT_WORKER_CODER_MODEL_API_BASE_URL=https://api.openai.com/v1
export REPOPILOT_WORKER_CODER_MODEL_API_KEY=...
export REPOPILOT_WORKER_CODER_MODEL_NAME=gpt-worker-coder
```

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `REPOPILOT_WORKER_CODER_MODEL_MODE` | `disabled` | `disabled` 保持安全规划草稿；`fixture` 使用固定 raw diff；`openai`/`openai-compatible` 调用 Chat Completions 兼容接口 |
| `REPOPILOT_WORKER_CODER_MODEL_PROVIDER` | `WORKER_CODER_FIXTURE` | 写入 `generate_patch` model call audit 的 provider |
| `REPOPILOT_WORKER_CODER_MODEL_NAME` | `worker-fixture-coder-v1` | 写入 `generate_patch` model call audit 的模型名 |
| `REPOPILOT_WORKER_CODER_MODEL_FIXTURE_RESPONSE` | 空 | fixture 模式下作为 raw Coder output 进入 diff parser |
| `REPOPILOT_WORKER_CODER_MODEL_API_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible API base URL |
| `REPOPILOT_WORKER_CODER_MODEL_API_KEY` / `OPENAI_API_KEY` | 空 | OpenAI-compatible 模式的 API key；不会写入 prompt/response 审计 |
| `REPOPILOT_WORKER_CODER_MODEL_TIMEOUT_SECONDS` | `120` | 模型 HTTP 请求超时 |
| `REPOPILOT_WORKER_CODER_MODEL_MAX_COMPLETION_TOKENS` | `4096` | 写入 Chat Completions 请求的 `max_completion_tokens` |
| `REPOPILOT_WORKER_CODER_MODEL_INSTRUCTION_ROLE` | `developer` | Coder 指令消息角色，只支持 `developer` 或 `system` |

Worker Coder 输出必须是一个 raw unified diff，或一个没有额外说明文字的 `diff`/`patch` 代码块。解析通过后会持久化为 `generationMode=LLM_CODER_DRAFT`，并继续进入 `validate_patch_safety(...)`、`run_patch_sandbox_tests(...)`、`review_patch(...)` 和 `mark_patch_ready_for_approval(...)`。解析失败时不会写入 patch。

## Backend Patch Callback

Worker nodes can persist generated patch drafts before the backend applies the usual safety, sandbox, review and approval gates:

```python
client.record_patch(
    run_id=303,
    patch=AgentPatchRecordRequest(
        diff_content="diff --git a/.repopilot/worker-plan.md b/.repopilot/worker-plan.md\n...",
        summary="Worker generated a patch draft",
        generation_mode="WORKER_SAFE_PLANNING_DRAFT",
        generation_provider="AGENT_WORKER",
        generation_model="worker-retrieval-plan-v1",
    ),
)
```

The backend binds the patch to the existing run/task, fills default branches when omitted and returns the standard `PatchRecordResponse`. After persistence, the worker can call `validate_patch_safety(...)`, `run_patch_sandbox_tests(...)`, `review_patch(...)` and `mark_patch_ready_for_approval(...)` to let the backend record the same safety, sandbox, automated review and approval checkpoint evidence used by the local executor. The approval-ready bridge only enters `WAITING_HUMAN_APPROVAL`; creating a PR still requires a human approval action, then the standard approval and pull-request APIs prepare the local target branch, commit and `DRAFT_READY` PR record.

## Backend Tool Bridge

Worker nodes can also read run-scoped repository context through the same internal token:

```python
from app.clients.backend_api import BackendApiClient

client = BackendApiClient()

context = client.load_run_context(run_id=303)
files = client.list_project_files(run_id=303, max_depth=6)
controller = client.read_project_file(
    run_id=303,
    path="src/main/java/com/example/demo/user/UserController.java",
)
search = client.search_code(run_id=303, query="User Controller", limit=8)
symbols = client.list_symbols(run_id=303, symbol_type="CONTROLLER")
```

These methods call backend internal endpoints scoped by `run_id`; the backend resolves the related task and project, so the worker does not need a user JWT or a raw `project_id` tool scope.
Each method also records a best-effort tool call audit entry through `record_tool_call(...)`; audit write failures are ignored so a logging outage does not break the main tool read.

## Initial Worker Nodes

When `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` is configured, `/runs/{run_id}/start` schedules a small background execution:

1. `load_task_context` reads run/task/project context, file samples and symbol samples, then records a SUCCESS step.
2. `ensure_index` checks that the run has file and Java symbol signals, then records index readiness evidence.
3. `plan_task` builds a deterministic Spring implementation plan, runs a few code searches for evidence, optionally records structured model-backed planning advice, then records a SUCCESS step.
4. `retrieve_context` reuses deterministic plan search queries, safely adds selected model-proposed queries, deduplicates code chunks, reads key file previews and records a SUCCESS step.
5. `generate_patch` creates a safe planning draft diff under `.repopilot/` by default; when Worker Coder is enabled, it parses the model raw unified diff into `LLM_CODER_DRAFT`. Both paths record model-call audit, persist the patch through `record_patch(...)`, record a SUCCESS step, call `validate_patch_safety(...)`, then `run_patch_sandbox_tests(...)` when safety passes, `review_patch(...)` when sandbox tests pass, and `mark_patch_ready_for_approval(...)` when review passes.

If no callback token is configured, `/start` remains a pure contract endpoint and does not run background nodes. This keeps local smoke tests and bridge-disabled development quiet.

The initial node chain is now executed through LangGraph `StateGraph` when the `langgraph` dependency is installed. Local development environments that have not installed optional worker dependencies fall back to the same sequential node order and expose that through `/health.graph_engine=SEQUENTIAL_FALLBACK`; real graph environments expose `/health.graph_engine=LANGGRAPH`.

## Worker Node Layout

The initial graph assembly stays in `app/graph/initial_nodes.py`, while node implementations live under `app/graph/nodes/`:

- `context.py`: `load_task_context` and `ensure_index`.
- `planning.py`: deterministic `plan_task`, optional Worker model planning note and `retrieve_context`.
- `patch.py`: deterministic `generate_patch` draft generation, optional Worker Coder diff parsing and post-patch gates.
- `common.py`: shared small helpers for previews, query handling and deduped values.

This keeps LangGraph wiring small and makes future model-backed nodes easier to replace one at a time.

Next implementation steps:

1. Add a Worker Planner node smoke with a local OpenAI-compatible stub.
2. Replace deterministic planning/patch draft logic with model-backed nodes one node at a time.
3. Exercise Worker-approved patches against real remote GitHub PR publishing once a token-backed demo repository is available.
