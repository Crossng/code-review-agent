MVP_GRAPH_NODES = [
    "load_task_context",
    "ensure_index",
    "plan_task",
    "retrieve_context",
    "generate_patch",
    "apply_patch",
    "run_tests",
    "review_patch",
    "wait_approval",
    "create_pr",
]


def graph_nodes() -> list[str]:
    return MVP_GRAPH_NODES.copy()

