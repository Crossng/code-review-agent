import sys
import unittest
from pathlib import Path
from unittest.mock import patch


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.graph.initial_nodes import build_initial_graph, run_initial_nodes_safely  # noqa: E402
from app.schemas import AgentRunStartRequest  # noqa: E402


class InitialGraphBuilderTest(unittest.TestCase):
    def test_initial_graph_keeps_expected_node_order(self):
        request = AgentRunStartRequest(
            task_id=303,
            project_id=202,
            user_request="给 User 模块新增分页查询接口",
            repo_path="/workspace/repos/202/source",
            base_branch="main",
        )

        graph = build_initial_graph(606, request, client=object())

        self.assertEqual(
            [node.name for node in graph.nodes],
            [
                "load_task_context",
                "ensure_index",
                "plan_task",
                "retrieve_context",
                "generate_patch",
            ],
        )

    def test_background_node_failure_marks_backend_run_failed(self):
        request = AgentRunStartRequest(
            task_id=303,
            project_id=202,
            user_request="给 User 模块新增分页查询接口",
            repo_path="/workspace/repos/202/source",
            base_branch="main",
        )
        fake_client = FakeBackendClient()

        class FailingGraph:
            def run(self, on_node_start=None):
                on_node_start("retrieve_context")
                raise RuntimeError("search backend failed")

        with patch("app.graph.initial_nodes.BackendApiClient", return_value=fake_client), \
                patch("app.graph.initial_nodes.build_initial_graph", return_value=FailingGraph()):
            run_initial_nodes_safely(606, request)

        self.assertEqual(len(fake_client.steps), 1)
        self.assertEqual(fake_client.steps[0].step_name, "retrieve_context")
        self.assertEqual(fake_client.steps[0].status, "FAILED")
        self.assertIn("search backend failed", fake_client.steps[0].error_message)
        self.assertEqual(len(fake_client.status_updates), 1)
        status = fake_client.status_updates[0]
        self.assertEqual(status.task_status, "FAILED_PATCH_GENERATION")
        self.assertEqual(status.run_status, "FAILED")
        self.assertEqual(status.complete_stream, True)
        self.assertEqual(status.stream_message, "Agent Worker 执行失败")


class FakeBackendClient:
    def __init__(self):
        self.steps = []
        self.status_updates = []

    def record_step(self, run_id, step):
        self.steps.append(step)
        return {"data": {"runId": run_id, "stepName": step.step_name}}

    def update_status(self, run_id, status):
        self.status_updates.append(status)
        return {"data": {"runId": run_id, "runStatus": status.run_status}}


if __name__ == "__main__":
    unittest.main()
