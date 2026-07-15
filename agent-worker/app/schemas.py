from pydantic import BaseModel, Field
from typing import Optional


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
