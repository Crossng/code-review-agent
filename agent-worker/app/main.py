from fastapi import BackgroundTasks, FastAPI

from app.config import settings
from app.graph.builder import graph_nodes
from app.graph.initial_nodes import run_initial_nodes_safely
from app.graph.runner import graph_execution_engine
from app.schemas import AgentRunStartRequest, AgentRunStartResponse, HealthResponse

app = FastAPI(title="RepoPilot Agent Worker", version="0.1.0")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="UP", service="agent-worker", graph_engine=graph_execution_engine())


@app.post("/runs/{run_id}/start", response_model=AgentRunStartResponse)
def start_run(run_id: int, request: AgentRunStartRequest, background_tasks: BackgroundTasks) -> AgentRunStartResponse:
    if settings.backend_callback_token:
        background_tasks.add_task(run_initial_nodes_safely, run_id, request)
    return AgentRunStartResponse(
        run_id=run_id,
        accepted=True,
        status="QUEUED",
        graph_nodes=graph_nodes(),
    )
