import json
import time
from dataclasses import dataclass
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from app.config import settings


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


class WorkerModelClient:
    def __init__(self, model_settings: Optional[WorkerModelClientSettings] = None) -> None:
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
        )

    def generate_text(self, step_name: str, prompt: dict[str, Any]) -> Optional[WorkerModelResult]:
        mode = (self.settings.mode or "disabled").strip().lower()
        if not mode or mode == "disabled":
            return None
        if mode == "fixture":
            return self._fixture_response(step_name, prompt)
        if mode in {"openai", "openai-compatible"}:
            return self._openai_compatible_response(step_name, prompt)
        raise ValueError(f"Unsupported worker model mode: {self.settings.mode}")

    def _fixture_response(self, step_name: str, prompt: dict[str, Any]) -> WorkerModelResult:
        text = (self.settings.fixture_response or "").strip()
        if not text:
            raise ValueError(
                "REPOPILOT_WORKER_MODEL_FIXTURE_RESPONSE is required when worker model mode is fixture."
            )

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

    def _openai_compatible_response(self, step_name: str, prompt: dict[str, Any]) -> WorkerModelResult:
        api_key = required(
            self.settings.api_key,
            "REPOPILOT_WORKER_MODEL_API_KEY is required when worker model mode is openai-compatible.",
        )
        model = required(
            self.settings.model,
            "REPOPILOT_WORKER_MODEL_NAME is required when worker model mode is openai-compatible.",
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
        )

    def _chat_completion_request(self, step_name: str, prompt: dict[str, Any], model: str) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": model,
            "messages": [
                {
                    "role": instruction_role(self.settings.instruction_role),
                    "content": planner_system_prompt(step_name),
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
            raise ValueError(f"Worker model returned HTTP {error.code}: {excerpt(detail)}") from error
        except URLError as error:
            raise ValueError(f"Worker model request failed: {error.reason}") from error
        except TimeoutError as error:
            raise ValueError("Worker model request timed out") from error
        try:
            decoded = json.loads(response_text)
        except json.JSONDecodeError as error:
            raise ValueError("Worker model returned invalid JSON") from error
        if not isinstance(decoded, dict):
            raise ValueError("Worker model returned a non-object JSON response")
        return decoded

    def _provider(self, default: str) -> str:
        provider = (self.settings.provider or "").strip()
        if not provider or (default == "OPENAI_COMPATIBLE" and provider == "WORKER_FIXTURE"):
            return default
        return provider


def planner_system_prompt(step_name: str) -> str:
    return (
        "You are RepoPilot PlannerAgent for Java and Spring Boot repositories.\n"
        f"Current step: {step_name}.\n"
        "Return a concise Chinese engineering plan summary only.\n"
        "Mention the likely modules to inspect or change, the validation path, and the approval checkpoint.\n"
        "Do not return code blocks, Markdown fences, unified diffs, secrets, or multiple alternatives.\n"
        "Keep the output grounded in the provided task, index signals, search results, and deterministic plan."
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
