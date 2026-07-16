# RepoPilot Agent Worker

This module will host the LangGraph workflow for long-running RepoPilot agent runs.

Current slice:

- `GET /health` returns worker health.
- `POST /runs/{run_id}/start` accepts a run contract and returns the planned MVP graph nodes.
- `../scripts/agent-worker-smoke.sh` starts or reuses the worker and verifies both contracts.

## Local Contract Smoke

```bash
../scripts/agent-worker-smoke.sh
../scripts/agent-worker-callback-smoke.sh
../scripts/agent-worker-tool-smoke.sh
../scripts/agent-worker-node-smoke.sh
```

The contract smoke script checks:

- FastAPI dependencies are importable.
- `/health` returns `status=UP` and `service=agent-worker`.
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
- The worker records `load_task_context`, `ensure_index`, deterministic `plan_task`, `retrieve_context` and `generate_patch` SUCCESS steps.
- The worker records a deterministic `generate_patch` model call and a `WORKER_SAFE_PLANNING_DRAFT` patch draft.
- Evidence is written to `output/agent-worker-node-smoke/last-run.json`.

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

## Backend Patch Callback

Worker nodes can persist generated patch drafts before the backend applies the usual safety, sandbox and approval gates:

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

The backend binds the patch to the existing run/task, fills default branches when omitted and returns the standard `PatchRecordResponse`.

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
3. `plan_task` builds a deterministic Spring implementation plan, runs a few code searches for evidence, then records a SUCCESS step.
4. `retrieve_context` reuses plan search queries, deduplicates code chunks, reads key file previews and records a SUCCESS step.
5. `generate_patch` creates a safe planning draft diff under `.repopilot/`, records a deterministic model-call audit entry, persists the draft through `record_patch(...)` and records a SUCCESS step.

If no callback token is configured, `/start` remains a pure contract endpoint and does not run background nodes. This keeps local smoke tests and bridge-disabled development quiet.

Next implementation steps:

1. Replace the lightweight graph runner with a real LangGraph graph once node contracts stabilize.
2. Attach future Worker model calls to `record_model_call(...)` automatically.
3. Connect Worker-generated patches to the backend safety, sandbox, review and approval chain.
