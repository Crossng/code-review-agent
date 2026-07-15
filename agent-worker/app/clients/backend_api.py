import json
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from app.config import settings
from app.schemas import AgentStatusUpdateRequest, AgentStepRecordRequest


class BackendApiError(RuntimeError):
    """Raised when the Agent Worker cannot write evidence back to the backend."""


class BackendApiClient:
    def __init__(
        self,
        base_url: Optional[str] = None,
        callback_token: Optional[str] = None,
        timeout_seconds: Optional[int] = None,
    ) -> None:
        self.base_url = (base_url or settings.backend_base_url).rstrip("/")
        self.callback_token = callback_token if callback_token is not None else settings.backend_callback_token
        self.timeout_seconds = timeout_seconds if timeout_seconds is not None else settings.backend_timeout_seconds

    def record_step(self, run_id: int, step: AgentStepRecordRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/steps",
            step.model_dump(exclude_none=True),
        )

    def update_status(self, run_id: int, status: AgentStatusUpdateRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/status",
            status.model_dump(exclude_none=True),
        )

    def _post_callback(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        if not self.callback_token:
            raise BackendApiError("Backend callback token is not configured")
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(
            f"{self.base_url}{path}",
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "X-RepoPilot-Worker-Token": self.callback_token,
            },
        )
        try:
            with urlopen(request, timeout=max(1, self.timeout_seconds)) as response:
                response_body = response.read().decode("utf-8")
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise BackendApiError(f"Backend callback failed with HTTP {error.code}: {detail}") from error
        except URLError as error:
            raise BackendApiError(f"Backend callback failed: {error.reason}") from error
        except TimeoutError as error:
            raise BackendApiError("Backend callback timed out") from error
        try:
            decoded = json.loads(response_body)
        except json.JSONDecodeError as error:
            raise BackendApiError("Backend callback returned invalid JSON") from error
        if not decoded.get("success"):
            raise BackendApiError(f"Backend callback rejected step: {decoded}")
        return decoded
