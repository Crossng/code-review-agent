from typing import Any, Optional, TypedDict


class AgentRunState(TypedDict, total=False):
    task_id: int
    project_id: int
    run_id: int
    repo_path: Optional[str]
    base_branch: str
    target_branch: str
    user_request: str
    project_summary: str
    plan: list[dict[str, Any]]
    retrieved_context: list[dict[str, Any]]
    diff: str
    test_result: Optional[dict[str, Any]]
    repair_attempts: int
    review_report: Optional[dict[str, Any]]
    approval_status: str
    loaded_context: dict[str, Any]
    index_status: dict[str, Any]
    plan_output: dict[str, Any]
    retrieval_output: dict[str, Any]
    patch_output: dict[str, Any]
