import json
import time
from dataclasses import dataclass
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from app.config import settings
from app.retry import RetryPolicy, call_with_retry, is_retryable_http_status


@dataclass(frozen=True)
class WorkerModelClientSettings:
    mode: str = "disabled"
    provider: str = ""
    model: str = "worker-fixture-plan-v1"
    fixture_response: str = ""
    api_base_url: str = "https://api.openai.com/v1"
    api_key: str = ""
    timeout_seconds: int = 120
    max_completion_tokens: int = 1200
    instruction_role: str = "developer"
    organization: str = ""
    project: str = ""
    retry_max_attempts: int = 3
    retry_backoff_seconds: float = 0.0


@dataclass(frozen=True)
class WorkerModelResult:
    provider: str
    model: str
    prompt: dict[str, Any]
    response: dict[str, Any]
    text: str
    duration_ms: int
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None
    retry_attempts: Optional[list[dict[str, Any]]] = None


class WorkerModelError(ValueError):
    def __init__(self, message: str, retryable: bool = False) -> None:
        super().__init__(message)
        self.retryable = retryable


class WorkerModelClient:
    def __init__(
        self,
        model_settings: Optional[WorkerModelClientSettings] = None,
        system_prompt_factory=None,
        fixture_required_message: str = (
            "REPOPILOT_WORKER_MODEL_FIXTURE_RESPONSE is required when worker model mode is fixture."
        ),
        api_key_required_message: str = (
            "REPOPILOT_WORKER_MODEL_API_KEY is required when worker model mode is openai-compatible."
        ),
        model_required_message: str = (
            "REPOPILOT_WORKER_MODEL_NAME is required when worker model mode is openai-compatible."
        ),
    ) -> None:
        self.settings = model_settings or WorkerModelClientSettings(
            mode=settings.worker_model_mode,
            provider=settings.worker_model_provider,
            model=settings.worker_model_name,
            fixture_response=settings.worker_model_fixture_response,
            api_base_url=settings.worker_model_api_base_url,
            api_key=settings.worker_model_api_key,
            timeout_seconds=settings.worker_model_timeout_seconds,
            max_completion_tokens=settings.worker_model_max_completion_tokens,
            instruction_role=settings.worker_model_instruction_role,
            organization=settings.worker_model_organization,
            project=settings.worker_model_project,
            retry_max_attempts=settings.worker_retry_max_attempts,
            retry_backoff_seconds=settings.worker_retry_backoff_seconds,
        )
        self.system_prompt_factory = system_prompt_factory or planner_system_prompt
        self.fixture_required_message = fixture_required_message
        self.api_key_required_message = api_key_required_message
        self.model_required_message = model_required_message

    def generate_text(self, step_name: str, prompt: dict[str, Any]) -> Optional[WorkerModelResult]:
        mode = (self.settings.mode or "disabled").strip().lower()
        if not mode or mode == "disabled":
            return None
        if mode == "fixture":
            return self._fixture_response(step_name, prompt)
        if mode in {"openai", "openai-compatible"}:
            retry_attempts: list[dict[str, Any]] = []
            return call_with_retry(
                lambda: self._openai_compatible_response(step_name, prompt, retry_attempts),
                RetryPolicy(
                    max_attempts=self.settings.retry_max_attempts,
                    backoff_seconds=self.settings.retry_backoff_seconds,
                ),
                on_retry=lambda attempt, error: retry_attempts.append(retry_attempt_summary(attempt, error)),
            )
        raise ValueError(f"Unsupported worker model mode: {self.settings.mode}")

    def _fixture_response(self, step_name: str, prompt: dict[str, Any]) -> WorkerModelResult:
        text = (self.settings.fixture_response or "").strip()
        if not text:
            raise ValueError(self.fixture_required_message)

        started_at = time.monotonic()
        response = {
            "mode": "fixture",
            "stepName": step_name,
            "text": text,
        }
        return WorkerModelResult(
            provider=self.settings.provider or "WORKER_FIXTURE",
            model=self.settings.model or "worker-fixture-plan-v1",
            prompt=prompt,
            response=response,
            text=text,
            duration_ms=max(0, int((time.monotonic() - started_at) * 1000)),
        )

    def _openai_compatible_response(
        self,
        step_name: str,
        prompt: dict[str, Any],
        retry_attempts: Optional[list[dict[str, Any]]] = None,
    ) -> WorkerModelResult:
        api_key = required(
            self.settings.api_key,
            self.api_key_required_message,
        )
        model = required(
            self.settings.model,
            self.model_required_message,
        )
        started_at = time.monotonic()
        request_body = self._chat_completion_request(step_name, prompt, model)
        response_body = self._post_chat_completion(request_body, api_key)
        text = extract_assistant_text(response_body)
        response_model = str(response_body.get("model") or model)
        usage = response_body.get("usage") if isinstance(response_body.get("usage"), dict) else {}
        return WorkerModelResult(
            provider=self._provider("OPENAI_COMPATIBLE"),
            model=response_model,
            prompt={
                "mode": "openai-compatible",
                "request": request_body,
            },
            response={
                "model": response_model,
                "choices": response_body.get("choices"),
                "usage": usage,
            },
            text=text,
            duration_ms=max(0, int((time.monotonic() - started_at) * 1000)),
            prompt_tokens=positive_int_or_none(usage.get("prompt_tokens")),
            completion_tokens=positive_int_or_none(usage.get("completion_tokens")),
            total_tokens=positive_int_or_none(usage.get("total_tokens")),
            retry_attempts=retry_attempts or None,
        )

    def _chat_completion_request(self, step_name: str, prompt: dict[str, Any], model: str) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "messages": [
                {
                    "role": instruction_role(self.settings.instruction_role),
                    "content": self.system_prompt_factory(step_name),
                },
                {
                    "role": "user",
                    "content": json.dumps(prompt, ensure_ascii=False, indent=2),
                },
            ],
        }
        if self.settings.max_completion_tokens > 0:
            body["max_completion_tokens"] = self.settings.max_completion_tokens
        return body

    def _post_chat_completion(self, request_body: dict[str, Any], api_key: str) -> dict[str, Any]:
        body = json.dumps(request_body, ensure_ascii=False).encode("utf-8")
        request = Request(
            f"{api_base_url(self.settings.api_base_url)}/chat/completions",
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        add_optional_header(request, "OpenAI-Organization", self.settings.organization)
        add_optional_header(request, "OpenAI-Project", self.settings.project)
        try:
            with urlopen(request, timeout=max(1, self.settings.timeout_seconds)) as response:
                response_text = response.read().decode("utf-8")
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise WorkerModelError(
                f"Worker model returned HTTP {error.code}: {excerpt(detail)}",
                retryable=is_retryable_http_status(error.code),
            ) from error
        except URLError as error:
            raise WorkerModelError(
                f"Worker model request failed: {error.reason}",
                retryable=True,
            ) from error
        except TimeoutError as error:
            raise WorkerModelError("Worker model request timed out", retryable=True) from error
        try:
            decoded = json.loads(response_text)
        except json.JSONDecodeError as error:
            raise WorkerModelError("Worker model returned invalid JSON") from error
        if not isinstance(decoded, dict):
            raise WorkerModelError("Worker model returned a non-object JSON response")
        return decoded

    def _provider(self, default: str) -> str:
        provider = (self.settings.provider or "").strip()
        if not provider or (default == "OPENAI_COMPATIBLE" and provider.endswith("_FIXTURE")):
            return default
        return provider


class WorkerCoderModelClient(WorkerModelClient):
    def __init__(self, model_settings: Optional[WorkerModelClientSettings] = None) -> None:
        super().__init__(
            model_settings
            or WorkerModelClientSettings(
                mode=settings.worker_coder_model_mode,
                provider=settings.worker_coder_model_provider,
                model=settings.worker_coder_model_name,
                fixture_response=settings.worker_coder_model_fixture_response,
                api_base_url=settings.worker_coder_model_api_base_url,
                api_key=settings.worker_coder_model_api_key,
                timeout_seconds=settings.worker_coder_model_timeout_seconds,
                max_completion_tokens=settings.worker_coder_model_max_completion_tokens,
                instruction_role=settings.worker_coder_model_instruction_role,
                organization=settings.worker_coder_model_organization,
                project=settings.worker_coder_model_project,
                retry_max_attempts=settings.worker_retry_max_attempts,
                retry_backoff_seconds=settings.worker_retry_backoff_seconds,
            ),
            system_prompt_factory=coder_system_prompt,
            fixture_required_message=(
                "REPOPILOT_WORKER_CODER_MODEL_FIXTURE_RESPONSE is required when worker coder model mode is fixture."
            ),
            api_key_required_message=(
                "REPOPILOT_WORKER_CODER_MODEL_API_KEY is required when worker coder model mode is openai-compatible."
            ),
            model_required_message=(
                "REPOPILOT_WORKER_CODER_MODEL_NAME is required when worker coder model mode is openai-compatible."
            ),
        )


def planner_system_prompt(step_name: str) -> str:
    return (
        "You are RepoPilot PlannerAgent for Java and Spring Boot repositories.\n"
        f"Current step: {step_name}.\n"
        "Return one JSON object only, without Markdown fences or explanations.\n"
        "Required fields: summary, steps, searchQueries, risks, testStrategy.\n"
        "steps must be a small ordered array of Chinese engineering actions.\n"
        "searchQueries must be short repository search strings grounded in the task and indexed code signals.\n"
        "Do not return code, unified diffs, secrets, or multiple alternatives.\n"
        "Keep the output grounded in the provided task, index signals, search results, and deterministic plan."
    )


def coder_system_prompt(step_name: str) -> str:
    return (
        "You are RepoPilot CoderAgent for Java and Spring Boot repositories.\n"
        f"Current step: {step_name}.\n"
        "Return only one raw unified diff.\n"
        "The first non-whitespace characters must be: diff --git\n"
        "Do not include Markdown fences, explanations, summaries, or multiple alternatives.\n"
        "Keep changes small, compile-safe, and limited to repository-relative paths.\n"
        "Do not edit .git, secret files, absolute paths, parent directories, or binary files.\n"
        "If the retrieved context is insufficient for a safe source-code change, create only a repository-local "
        "planning file under .repopilot/ explaining the missing context and validation needed."
    )


def instruction_role(value: str) -> str:
    role = (value or "developer").strip().lower()
    if role in {"developer", "system"}:
        return role
    raise ValueError("REPOPILOT_WORKER_MODEL_INSTRUCTION_ROLE must be developer or system.")


def api_base_url(value: str) -> str:
    url = (value or "https://api.openai.com/v1").strip()
    while url.endswith("/"):
        url = url[:-1]
    return url


def required(value: str, message: str) -> str:
    if value is None or not value.strip():
        raise ValueError(message)
    return value.strip()


def add_optional_header(request: Request, name: str, value: str) -> None:
    if value and value.strip():
        request.add_header(name, value.strip())


def extract_assistant_text(body: dict[str, Any]) -> str:
    choices = body.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError("Worker model response did not include choices.")
    first_choice = choices[0]
    if not isinstance(first_choice, dict):
        raise ValueError("Worker model response choice is not an object.")
    message = first_choice.get("message")
    if not isinstance(message, dict):
        raise ValueError("Worker model response did not include assistant message.")
    text = content_text(message.get("content")).strip()
    if not text:
        raise ValueError("Worker model response did not include assistant content.")
    return text


def content_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts = []
    for part in content:
        if isinstance(part, dict) and isinstance(part.get("text"), str):
            parts.append(part["text"])
    return "".join(parts)


def positive_int_or_none(value: Any) -> Optional[int]:
    if isinstance(value, int) and value >= 0:
        return value
    return None


def excerpt(value: str, limit: int = 2000) -> str:
    text = value.replace("\r\n", "\n")
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n..."


def retry_attempt_summary(attempt: int, error: Exception) -> dict[str, Any]:
    return {
        "attempt": attempt,
        "errorType": error.__class__.__name__,
        "message": excerpt(str(error), 500),
        "retryable": bool(getattr(error, "retryable", False)),
    }
