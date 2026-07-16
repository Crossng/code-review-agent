from app.clients.backend_api import BackendApiClient
from app.graph.nodes.context import ensure_index, load_task_context
from app.graph.nodes.patch import generate_patch
from app.graph.nodes.planning import plan_task, retrieve_context
from app.graph.runner import WorkerGraphNode, WorkerGraphRunner
from app.schemas import AgentRunStartRequest, AgentStepRecordRequest


def run_initial_nodes_safely(run_id: int, request: AgentRunStartRequest) -> None:
    client = BackendApiClient()
    current_step = "load_task_context"

    def update_current_step(step_name: str) -> None:
        nonlocal current_step
        current_step = step_name

    try:
        build_initial_graph(run_id, request, client).run(on_node_start=update_current_step)
    except Exception as error:  # noqa: BLE001 - background task must not crash the worker process
        try:
            client.record_step(
                run_id,
                AgentStepRecordRequest(
                    step_name=current_step,
                    status="FAILED",
                    input={"runId": run_id, "source": "agent-worker"},
                    error_message=str(error)[:4000],
                ),
            )
        except Exception:
            return


def build_initial_graph(
    run_id: int,
    request: AgentRunStartRequest,
    client: BackendApiClient,
) -> WorkerGraphRunner:
    return WorkerGraphRunner(
        [
            WorkerGraphNode(
                "load_task_context",
                lambda state: {
                    "loaded_context": load_task_context(run_id, request, client),
                },
            ),
            WorkerGraphNode(
                "ensure_index",
                lambda state: {
                    "index_status": ensure_index(run_id, state["loaded_context"], request, client),
                },
            ),
            WorkerGraphNode(
                "plan_task",
                lambda state: {
                    "plan_output": plan_task(
                        run_id,
                        state["loaded_context"],
                        request,
                        client,
                    ),
                },
            ),
            WorkerGraphNode(
                "retrieve_context",
                lambda state: {
                    "retrieval_output": retrieve_context(
                        run_id,
                        state["loaded_context"],
                        state["plan_output"],
                        request,
                        client,
                    ),
                },
            ),
            WorkerGraphNode(
                "generate_patch",
                lambda state: {
                    "patch_output": generate_patch(
                        run_id,
                        state["loaded_context"],
                        state["index_status"],
                        state["plan_output"],
                        state["retrieval_output"],
                        request,
                        client,
                    ),
                },
            ),
        ]
    )
