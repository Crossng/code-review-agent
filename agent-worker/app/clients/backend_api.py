import json
import time
from typing import Any, Callable, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from app.config import settings
from app.retry import RetryPolicy, call_with_retry, is_retryable_http_status
from app.schemas import (
    AgentModelCallRecordRequest,
    AgentPatchRecordRequest,
    AgentStatusUpdateRequest,
    AgentStepRecordRequest,
    AgentToolCallRecordRequest,
)


class BackendApiError(RuntimeError):
    """Raised when the Agent Worker cannot call an internal backend API."""

    def __init__(self, message: str, retryable: bool = False) -> None:
        super().__init__(message)
        self.retryable = retryable


class BackendApiClient:
    def __init__(
        self,
        base_url: Optional[str] = None,
        callback_token: Optional[str] = None,
        timeout_seconds: Optional[int] = None,
        retry_max_attempts: Optional[int] = None,
        retry_backoff_seconds: Optional[float] = None,
    ) -> None:
        self.base_url = (base_url or settings.backend_base_url).rstrip("/")
        self.callback_token = callback_token if callback_token is not None else settings.backend_callback_token
        self.timeout_seconds = timeout_seconds if timeout_seconds is not None else settings.backend_timeout_seconds
        self.retry_policy = RetryPolicy(
            max_attempts=retry_max_attempts
            if retry_max_attempts is not None
            else settings.worker_retry_max_attempts,
            backoff_seconds=retry_backoff_seconds
            if retry_backoff_seconds is not None
            else settings.worker_retry_backoff_seconds,
        )

    def record_step(self, run_id: int, step: AgentStepRecordRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/steps",
            step.model_dump(exclude_none=True),
        )

    def record_tool_call(self, run_id: int, tool_call: AgentToolCallRecordRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/tool-calls",
            tool_call.model_dump(exclude_none=True),
        )

    def record_model_call(self, run_id: int, model_call: AgentModelCallRecordRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/model-calls",
            model_call.model_dump(exclude_none=True),
        )

    def record_patch(self, run_id: int, patch: AgentPatchRecordRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/patches",
            patch.model_dump(exclude_none=True),
        )

    def validate_patch_safety(self, run_id: int, patch_id: int) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/safety",
            {},
        )

    def run_patch_sandbox_tests(self, run_id: int, patch_id: int) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/sandbox-tests",
            {},
        )

    def review_patch(self, run_id: int, patch_id: int) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/review",
            {},
        )

    def mark_patch_ready_for_approval(self, run_id: int, patch_id: int) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/patches/{patch_id}/approval-ready",
            {},
        )

    def update_status(self, run_id: int, status: AgentStatusUpdateRequest) -> dict[str, Any]:
        return self._post_callback(
            f"/api/internal/agent-worker/runs/{run_id}/status",
            status.model_dump(exclude_none=True),
        )

    def load_run_context(self, run_id: int) -> dict[str, Any]:
        return self._get_tool(
            run_id,
            "load_run_context",
            f"/api/internal/agent-worker/runs/{run_id}/context",
            None,
            summarize_run_context,
        )

    def list_project_files(self, run_id: int, max_depth: int = 6) -> list[dict[str, Any]]:
        response = self._get_tool(
            run_id,
            "list_project_files",
            f"/api/internal/agent-worker/runs/{run_id}/project/files",
            {"maxDepth": max_depth},
            summarize_project_files,
        )
        return list(response)

    def read_project_file(self, run_id: int, path: str) -> dict[str, Any]:
        return self._get_tool(
            run_id,
            "read_project_file",
            f"/api/internal/agent-worker/runs/{run_id}/project/file",
            {"path": path},
            summarize_file_content,
        )

    def search_code(self, run_id: int, query: str, limit: int = 8) -> dict[str, Any]:
        return self._get_tool(
            run_id,
            "search_code",
            f"/api/internal/agent-worker/runs/{run_id}/project/search",
            {"query": query, "limit": limit},
            summarize_search_response,
        )

    def list_symbols(self, run_id: int, symbol_type: Optional[str] = None) -> list[dict[str, Any]]:
        params = {"type": symbol_type} if symbol_type else None
        response = self._get_tool(
            run_id,
            "list_symbols",
            f"/api/internal/agent-worker/runs/{run_id}/project/symbols",
            params,
            summarize_symbols,
        )
        return list(response)

    def _get_internal(
        self,
        path: str,
        params: Optional[dict[str, Any]] = None,
        retry_attempts: Optional[list[dict[str, Any]]] = None,
    ) -> Any:
        query = urlencode({key: value for key, value in (params or {}).items() if value is not None})
        request_path = f"{path}?{query}" if query else path
        return call_with_retry(
            lambda: self._request("GET", request_path, allow_retryable_errors=True),
            self.retry_policy,
            on_retry=lambda attempt, error: append_retry_attempt(retry_attempts, attempt, error),
        ).get("data")

    def _get_tool(
        self,
        run_id: int,
        tool_name: str,
        path: str,
        params: Optional[dict[str, Any]],
        output_summary: Callable[[Any], dict[str, Any]],
    ) -> Any:
        input_payload = {key: value for key, value in (params or {}).items() if value is not None}
        retry_attempts: list[dict[str, Any]] = []
        started_at = time.monotonic()
        try:
            response = self._get_internal(path, params, retry_attempts)
            output = output_summary(response)
            if retry_attempts:
                output["retryAttempts"] = retry_attempts
                output["retryAttemptCount"] = len(retry_attempts)
            self._record_tool_call_quietly(
                run_id,
                AgentToolCallRecordRequest(
                    tool_name=tool_name,
                    status="SUCCESS",
                    input=input_payload,
                    output=output,
                    duration_ms=elapsed_ms(started_at),
                ),
            )
            return response
        except Exception as error:
            message = str(error)[:4000]
            output: dict[str, Any] = {"error": message[:1000]}
            if retry_attempts:
                output["retryAttempts"] = retry_attempts
                output["retryAttemptCount"] = len(retry_attempts)
            self._record_tool_call_quietly(
                run_id,
                AgentToolCallRecordRequest(
                    tool_name=tool_name,
                    status="FAILED",
                    input=input_payload,
                    output=output,
                    duration_ms=elapsed_ms(started_at),
                    error_message=message,
                ),
            )
            raise

    def _record_tool_call_quietly(self, run_id: int, tool_call: AgentToolCallRecordRequest) -> None:
        try:
            self.record_tool_call(run_id, tool_call)
        except Exception:
            return

    def _post_callback(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", path, payload)

    def _request(
        self,
        method: str,
        path: str,
        payload: Optional[dict[str, Any]] = None,
        allow_retryable_errors: bool = False,
    ) -> Any:
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
            raise BackendApiError(
                f"Backend internal API failed with HTTP {error.code}: {detail}",
                retryable=allow_retryable_errors and is_retryable_http_status(error.code),
            ) from error
        except URLError as error:
            raise BackendApiError(
                f"Backend internal API failed: {error.reason}",
                retryable=allow_retryable_errors,
            ) from error
        except TimeoutError as error:
            raise BackendApiError(
                "Backend internal API timed out",
                retryable=allow_retryable_errors,
            ) from error
        try:
            decoded = json.loads(response_body)
        except json.JSONDecodeError as error:
            raise BackendApiError("Backend internal API returned invalid JSON") from error
        if not decoded.get("success"):
            raise BackendApiError(f"Backend internal API rejected request: {decoded}")
        return decoded


def elapsed_ms(started_at: float) -> int:
    return max(0, int((time.monotonic() - started_at) * 1000))


def append_retry_attempt(
    retry_attempts: Optional[list[dict[str, Any]]],
    attempt: int,
    error: Exception,
) -> None:
    if retry_attempts is None:
        return
    retry_attempts.append(
        {
            "attempt": attempt,
            "errorType": error.__class__.__name__,
            "message": text_preview(str(error), 500),
            "retryable": bool(getattr(error, "retryable", False)),
        }
    )


def summarize_run_context(context: dict[str, Any]) -> dict[str, Any]:
    return {
        "runId": context.get("runId"),
        "taskId": context.get("taskId"),
        "projectId": context.get("projectId"),
        "repoFullName": context.get("repoFullName"),
        "taskType": context.get("taskType"),
        "title": context.get("title"),
    }


def summarize_project_files(files: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "fileCount": len(files),
        "sampleFiles": [
            {
                "path": file.get("path"),
                "type": file.get("type"),
                "size": file.get("size"),
            }
            for file in files[:10]
        ],
    }


def summarize_file_content(file_response: dict[str, Any]) -> dict[str, Any]:
    content = str(file_response.get("content") or "")
    return {
        "path": file_response.get("path"),
        "size": file_response.get("size"),
        "contentPreview": text_preview(content, 500),
    }


def summarize_search_response(search_response: dict[str, Any]) -> dict[str, Any]:
    results = list(search_response.get("results") or [])
    return {
        "query": search_response.get("query"),
        "limit": search_response.get("limit"),
        "resultCount": len(results),
        "topFiles": unique_values(result.get("filePath") for result in results)[:5],
        "chunkIds": [result.get("chunkId") for result in results[:5]],
    }


def summarize_symbols(symbols: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "symbolCount": len(symbols),
        "sampleSymbols": [
            {
                "filePath": symbol.get("filePath"),
                "symbolType": symbol.get("symbolType"),
                "name": symbol.get("name"),
                "qualifiedName": symbol.get("qualifiedName"),
                "startLine": symbol.get("startLine"),
                "endLine": symbol.get("endLine"),
            }
            for symbol in symbols[:10]
        ],
    }


def text_preview(value: str, limit: int) -> str:
    text = value.replace("\r\n", "\n")
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n..."


def unique_values(values: Any) -> list[Any]:
    unique = []
    for value in values:
        if value is not None and value not in unique:
            unique.append(value)
    return unique
