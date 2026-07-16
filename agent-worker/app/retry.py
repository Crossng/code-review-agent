import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Optional, TypeVar


T = TypeVar("T")


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 1
    backoff_seconds: float = 0.0

    def normalized(self) -> "RetryPolicy":
        return RetryPolicy(
            max_attempts=max(1, int(self.max_attempts or 1)),
            backoff_seconds=max(0.0, float(self.backoff_seconds or 0.0)),
        )


def call_with_retry(
    operation: Callable[[], T],
    policy: RetryPolicy,
    is_retryable: Optional[Callable[[Exception], bool]] = None,
) -> T:
    retry_policy = policy.normalized()
    classifier = is_retryable or is_retryable_worker_error
    attempt = 1
    while True:
        try:
            return operation()
        except Exception as error:
            if attempt >= retry_policy.max_attempts or not classifier(error):
                raise
            if retry_policy.backoff_seconds > 0:
                time.sleep(retry_policy.backoff_seconds * attempt)
            attempt += 1


def is_retryable_worker_error(error: Exception) -> bool:
    return bool(getattr(error, "retryable", False))


def is_retryable_http_status(status_code: int) -> bool:
    return status_code in {408, 425, 429} or 500 <= status_code <= 599
