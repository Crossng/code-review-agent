import json
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from app.config import settings
from app.schemas import AgentStatusUpdateRequest, AgentStepRecordRequest


class BackendApiError(RuntimeError):
    """Raised when the Agent Worker cannot call an internal backend API."""


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

    def load_run_context(self, run_id: int) -> dict[str, Any]:
        return self._get_internal(f"/api/internal/agent-worker/runs/{run_id}/context")

    def list_project_files(self, run_id: int, max_depth: int = 6) -> list[dict[str, Any]]:
        response = self._get_internal(
            f"/api/internal/agent-worker/runs/{run_id}/project/files",
            {"maxDepth": max_depth},
        )
        return list(response)

    def read_project_file(self, run_id: int, path: str) -> dict[str, Any]:
        return self._get_internal(
            f"/api/internal/agent-worker/runs/{run_id}/project/file",
            {"path": path},
        )

    def search_code(self, run_id: int, query: str, limit: int = 8) -> dict[str, Any]:
        return self._get_internal(
            f"/api/internal/agent-worker/runs/{run_id}/project/search",
            {"query": query, "limit": limit},
        )

    def list_symbols(self, run_id: int, symbol_type: Optional[str] = None) -> list[dict[str, Any]]:
        params = {"type": symbol_type} if symbol_type else None
        response = self._get_internal(
            f"/api/internal/agent-worker/runs/{run_id}/project/symbols",
            params,
        )
        return list(response)

    def _get_internal(self, path: str, params: Optional[dict[str, Any]] = None) -> Any:
        query = urlencode({key: value for key, value in (params or {}).items() if value is not None})
        request_path = f"{path}?{query}" if query else path
        return self._request("GET", request_path).get("data")

    def _post_callback(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", path, payload)

    def _request(self, method: str, path: str, payload: Optional[dict[str, Any]] = None) -> Any:
        if not self.callback_token:
            raise BackendApiError("Backend callback token is not configured")
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"X-RepoPilot-Worker-Token": self.callback_token}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(
            f"{self.base_url}{path}",
            data=body,
            method=method,
            headers=headers,
        )
        try:
            with urlopen(request, timeout=max(1, self.timeout_seconds)) as response:
                response_body = response.read().decode("utf-8")
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise BackendApiError(f"Backend internal API failed with HTTP {error.code}: {detail}") from error
        except URLError as error:
            raise BackendApiError(f"Backend internal API failed: {error.reason}") from error
        except TimeoutError as error:
            raise BackendApiError("Backend internal API timed out") from error
        try:
            decoded = json.loads(response_body)
        except json.JSONDecodeError as error:
            raise BackendApiError("Backend internal API returned invalid JSON") from error
        if not decoded.get("success"):
            raise BackendApiError(f"Backend internal API rejected request: {decoded}")
        return decoded
