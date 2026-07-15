# RepoPilot Agent Worker

This module will host the LangGraph workflow for long-running RepoPilot agent runs.

Current slice:

- `GET /health` returns worker health.
- `POST /runs/{run_id}/start` accepts a run contract and returns the planned MVP graph nodes.
- `../scripts/agent-worker-smoke.sh` starts or reuses the worker and verifies both contracts.

## Local Contract Smoke

```bash
../scripts/agent-worker-smoke.sh
```

The smoke script checks:

- FastAPI dependencies are importable.
- `/health` returns `status=UP` and `service=agent-worker`.
- `/runs/303/start` returns `accepted=true`, `status=QUEUED` and the expected MVP graph node list.
- Evidence is written to `output/agent-worker-smoke/last-run.json`.

Next implementation steps:

1. Add Backend API client for run and step updates.
2. Add MCP client for repository tools.
3. Implement LangGraph nodes from `docs/technical/agent-workflow.md`.
4. Persist step events back to the Spring Boot backend.
