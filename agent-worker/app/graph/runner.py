from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Optional

from app.graph.state import AgentRunState


GraphState = dict[str, Any]
NodeHandler = Callable[[GraphState], GraphState]
NodeStartHandler = Callable[[str], None]
LANGGRAPH_ENGINE = "LANGGRAPH"
SEQUENTIAL_FALLBACK_ENGINE = "SEQUENTIAL_FALLBACK"


@dataclass(frozen=True)
class WorkerGraphNode:
    name: str
    handler: NodeHandler


class WorkerGraphRunner:
    def __init__(self, nodes: list[WorkerGraphNode], prefer_langgraph: bool = True):
        self.nodes = nodes
        self.prefer_langgraph = prefer_langgraph

    def run(
        self,
        initial_state: Optional[GraphState] = None,
        on_node_start: Optional[NodeStartHandler] = None,
    ) -> GraphState:
        if self.prefer_langgraph:
            langgraph_app = self._compile_langgraph(on_node_start)
            if langgraph_app is not None:
                return dict(langgraph_app.invoke(dict(initial_state or {})))
        return self._run_sequential(initial_state, on_node_start)

    def _run_sequential(
        self,
        initial_state: Optional[GraphState] = None,
        on_node_start: Optional[NodeStartHandler] = None,
    ) -> GraphState:
        state = dict(initial_state or {})
        for node in self.nodes:
            if on_node_start:
                on_node_start(node.name)
            partial_state = node.handler(state)
            state.update(partial_state)
        return state

    def _compile_langgraph(self, on_node_start: Optional[NodeStartHandler]):
        if not self.nodes:
            return None
        try:
            from langgraph.graph import END, StateGraph
        except ImportError:
            return None

        graph = StateGraph(AgentRunState)
        for node in self.nodes:
            graph.add_node(node.name, self._langgraph_node_handler(node, on_node_start))
        graph.set_entry_point(self.nodes[0].name)
        for index, node in enumerate(self.nodes[:-1]):
            graph.add_edge(node.name, self.nodes[index + 1].name)
        graph.add_edge(self.nodes[-1].name, END)
        return graph.compile()

    @staticmethod
    def _langgraph_node_handler(
        node: WorkerGraphNode,
        on_node_start: Optional[NodeStartHandler],
    ) -> NodeHandler:
        def run_node(state: GraphState) -> GraphState:
            if on_node_start:
                on_node_start(node.name)
            return node.handler(dict(state))

        return run_node


def graph_execution_engine() -> str:
    try:
        from langgraph.graph import StateGraph  # noqa: F401
    except ImportError:
        return SEQUENTIAL_FALLBACK_ENGINE
    return LANGGRAPH_ENGINE
