from fastapi import FastAPI

from app.graph.builder import graph_nodes
from app.schemas import AgentRunStartRequest, AgentRunStartResponse, HealthResponse

app = FastAPI(title="RepoPilot Agent Worker", version="0.1.0")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="UP", service="agent-worker")


@app.post("/runs/{run_id}/start", response_model=AgentRunStartResponse)
def start_run(run_id: int, request: AgentRunStartRequest) -> AgentRunStartResponse:
    return AgentRunStartResponse(
        run_id=run_id,
        accepted=True,
        status="QUEUED",
        graph_nodes=graph_nodes(),
    )

