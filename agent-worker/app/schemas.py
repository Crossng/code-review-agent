from pydantic import BaseModel, Field
from typing import Any, Literal, Optional


class AgentRunStartRequest(BaseModel):
    task_id: int = Field(gt=0)
    project_id: int = Field(gt=0)
    user_request: str = Field(min_length=1)
    repo_path: Optional[str] = None
    base_branch: str = "main"


class AgentRunStartResponse(BaseModel):
    run_id: int
    accepted: bool
    status: str
    graph_nodes: list[str]


class HealthResponse(BaseModel):
    status: str
    service: str


class AgentStepRecordRequest(BaseModel):
    step_name: str = Field(min_length=1, max_length=120)
    status: Literal["PENDING", "RUNNING", "SUCCESS", "FAILED"]
    input: Optional[dict[str, Any]] = None
    output: Optional[dict[str, Any]] = None
    error_message: Optional[str] = Field(default=None, max_length=4000)


class AgentToolCallRecordRequest(BaseModel):
    tool_name: str = Field(min_length=1, max_length=120)
    status: Literal["SUCCESS", "FAILED"]
    input: Optional[dict[str, Any]] = None
    output: Optional[dict[str, Any]] = None
    duration_ms: Optional[int] = Field(default=None, ge=0)
    error_message: Optional[str] = Field(default=None, max_length=4000)
    started_at: Optional[str] = None
    finished_at: Optional[str] = None


class AgentModelCallRecordRequest(BaseModel):
    model_config = {"protected_namespaces": ()}

    step_name: str = Field(min_length=1, max_length=120)
    model_provider: str = Field(min_length=1, max_length=120)
    model_name: str = Field(min_length=1, max_length=200)
    status: Literal["SUCCESS", "FAILED"]
    prompt: Optional[dict[str, Any]] = None
    response: Optional[dict[str, Any]] = None
    prompt_tokens: Optional[int] = Field(default=None, ge=0)
    completion_tokens: Optional[int] = Field(default=None, ge=0)
    total_tokens: Optional[int] = Field(default=None, ge=0)
    duration_ms: Optional[int] = Field(default=None, ge=0)
    error_message: Optional[str] = Field(default=None, max_length=4000)
    started_at: Optional[str] = None
    finished_at: Optional[str] = None


class AgentPatchRecordRequest(BaseModel):
    base_branch: Optional[str] = Field(default=None, max_length=200)
    target_branch: Optional[str] = Field(default=None, max_length=200)
    diff_content: str = Field(min_length=1)
    summary: Optional[str] = Field(default=None, max_length=4000)
    generation_mode: str = Field(min_length=1, max_length=120)
    generation_provider: str = Field(min_length=1, max_length=120)
    generation_model: Optional[str] = Field(default=None, max_length=200)


class AgentStatusUpdateRequest(BaseModel):
    task_status: Optional[
        Literal[
            "CREATED",
            "REPO_INDEXING",
            "PLANNING",
            "RETRIEVING_CONTEXT",
            "GENERATING_PATCH",
            "APPLYING_PATCH_IN_SANDBOX",
            "RUNNING_TESTS",
            "REPAIRING",
            "REVIEWING_PATCH",
            "WAITING_HUMAN_APPROVAL",
            "CREATING_PULL_REQUEST",
            "DONE",
            "FAILED_REPO_CLONE",
            "FAILED_INDEXING",
            "FAILED_CONTEXT_RETRIEVAL",
            "FAILED_PATCH_GENERATION",
            "FAILED_TEST",
            "FAILED_PR_CREATION",
            "CANCELLED",
        ]
    ] = None
    run_status: Optional[Literal["RUNNING", "SUCCESS", "FAILED", "CANCELLED"]] = None
    error_message: Optional[str] = Field(default=None, max_length=4000)
    stream_message: Optional[str] = Field(default=None, max_length=4000)
    complete_stream: bool = False
