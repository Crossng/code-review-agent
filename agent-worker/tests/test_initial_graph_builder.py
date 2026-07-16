import sys
import unittest
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.graph.initial_nodes import build_initial_graph  # noqa: E402
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


if __name__ == "__main__":
    unittest.main()
