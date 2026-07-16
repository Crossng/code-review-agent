from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Optional


GraphState = dict[str, Any]
NodeHandler = Callable[[GraphState], GraphState]
NodeStartHandler = Callable[[str], None]


@dataclass(frozen=True)
class WorkerGraphNode:
    name: str
    handler: NodeHandler


class WorkerGraphRunner:
    def __init__(self, nodes: list[WorkerGraphNode]):
        self.nodes = nodes

    def run(
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
