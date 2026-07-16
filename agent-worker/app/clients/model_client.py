import time
from dataclasses import dataclass
from typing import Any, Optional

from app.config import settings


@dataclass(frozen=True)
class WorkerModelClientSettings:
    mode: str = "disabled"
    provider: str = "WORKER_FIXTURE"
    model: str = "worker-fixture-plan-v1"
    fixture_response: str = ""


@dataclass(frozen=True)
class WorkerModelResult:
    provider: str
    model: str
    prompt: dict[str, Any]
    response: dict[str, Any]
    text: str
    duration_ms: int


class WorkerModelClient:
    def __init__(self, model_settings: Optional[WorkerModelClientSettings] = None) -> None:
        self.settings = model_settings or WorkerModelClientSettings(
            mode=settings.worker_model_mode,
            provider=settings.worker_model_provider,
            model=settings.worker_model_name,
            fixture_response=settings.worker_model_fixture_response,
        )

    def generate_text(self, step_name: str, prompt: dict[str, Any]) -> Optional[WorkerModelResult]:
        mode = (self.settings.mode or "disabled").strip().lower()
        if mode == "disabled":
            return None
        if mode == "fixture":
            return self._fixture_response(step_name, prompt)
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
