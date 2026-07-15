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
```

The contract smoke script checks:

- FastAPI dependencies are importable.
- `/health` returns `status=UP` and `service=agent-worker`.
- `/runs/303/start` returns `accepted=true`, `status=QUEUED` and the expected MVP graph node list.
- Evidence is written to `output/agent-worker-smoke/last-run.json`.

The callback smoke script checks:

- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/steps`.
- `BackendApiClient` posts to `/api/internal/agent-worker/runs/{run_id}/status`.
- `X-RepoPilot-Worker-Token` is attached.
- Step and status payloads use the backend callback contract.
- Evidence is written to `output/agent-worker-callback-smoke/last-run.json`.

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

Next implementation steps:

1. Add MCP client for repository tools.
2. Implement `load_task_context` and `plan_task` LangGraph nodes from `docs/technical/agent-workflow.md`.
3. Persist tool call and model call events back to the Spring Boot backend.
