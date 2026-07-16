import sys
import types
import unittest
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.graph.runner import (  # noqa: E402
    LANGGRAPH_ENGINE,
    SEQUENTIAL_FALLBACK_ENGINE,
    WorkerGraphNode,
    WorkerGraphRunner,
    graph_execution_engine,
)
from app.graph.state import AgentRunState  # noqa: E402


class WorkerGraphRunnerTest(unittest.TestCase):
    def test_sequential_fallback_runs_nodes_in_order(self):
        starts = []
        runner = WorkerGraphRunner(
            [
                WorkerGraphNode("first", lambda state: {"value": "a"}),
                WorkerGraphNode("second", lambda state: {"value": state["value"] + "b"}),
            ],
            prefer_langgraph=False,
        )

        result = runner.run(on_node_start=starts.append)

        self.assertEqual(starts, ["first", "second"])
        self.assertEqual(result["value"], "ab")

    def test_langgraph_engine_compiles_state_graph_when_available(self):
        old_langgraph = sys.modules.get("langgraph")
        old_langgraph_graph = sys.modules.get("langgraph.graph")
        fake_graph_module = types.ModuleType("langgraph.graph")
        fake_parent_module = types.ModuleType("langgraph")
        fake_graph_module.END = "__end__"
        compiled_graphs = []

        class FakeCompiledGraph:
            def __init__(self, graph):
                self.graph = graph

            def invoke(self, state):
                current = self.graph.entry_point
                next_state = dict(state)
                while current != fake_graph_module.END:
                    partial_state = self.graph.nodes[current](next_state)
                    next_state.update(partial_state)
                    current = self.graph.edges[current]
                return next_state

        class FakeStateGraph:
            def __init__(self, schema):
                self.schema = schema
                self.nodes = {}
                self.edges = {}
                self.entry_point = None

            def add_node(self, name, handler):
                self.nodes[name] = handler

            def set_entry_point(self, name):
                self.entry_point = name

            def add_edge(self, source, target):
                self.edges[source] = target

            def compile(self):
                compiled_graphs.append(self)
                return FakeCompiledGraph(self)

        fake_graph_module.StateGraph = FakeStateGraph
        fake_parent_module.graph = fake_graph_module
        sys.modules["langgraph"] = fake_parent_module
        sys.modules["langgraph.graph"] = fake_graph_module
        try:
            starts = []
            runner = WorkerGraphRunner(
                [
                    WorkerGraphNode("load_task_context", lambda state: {"loaded_context": {"ok": True}}),
                    WorkerGraphNode(
                        "ensure_index",
                        lambda state: {
                            "index_status": {
                                "indexReady": state["loaded_context"]["ok"],
                            }
                        },
                    ),
                ],
            )

            result = runner.run({"run_id": 606}, on_node_start=starts.append)

            self.assertEqual(graph_execution_engine(), LANGGRAPH_ENGINE)
            self.assertEqual(starts, ["load_task_context", "ensure_index"])
            self.assertEqual(result["run_id"], 606)
            self.assertEqual(result["index_status"]["indexReady"], True)
            self.assertEqual(len(compiled_graphs), 1)
            self.assertIs(compiled_graphs[0].schema, AgentRunState)
            self.assertEqual(
                compiled_graphs[0].edges,
                {
                    "load_task_context": "ensure_index",
                    "ensure_index": fake_graph_module.END,
                },
            )
        finally:
            if old_langgraph is None:
                sys.modules.pop("langgraph", None)
            else:
                sys.modules["langgraph"] = old_langgraph
            if old_langgraph_graph is None:
                sys.modules.pop("langgraph.graph", None)
            else:
                sys.modules["langgraph.graph"] = old_langgraph_graph

    def test_graph_execution_engine_reports_fallback_without_langgraph(self):
        old_langgraph = sys.modules.pop("langgraph", None)
        old_langgraph_graph = sys.modules.pop("langgraph.graph", None)
        try:
            self.assertIn(graph_execution_engine(), {LANGGRAPH_ENGINE, SEQUENTIAL_FALLBACK_ENGINE})
        finally:
            if old_langgraph is not None:
                sys.modules["langgraph"] = old_langgraph
            if old_langgraph_graph is not None:
                sys.modules["langgraph.graph"] = old_langgraph_graph


if __name__ == "__main__":
    unittest.main()
