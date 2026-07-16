import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.clients.model_client import WorkerModelClient, WorkerModelClientSettings  # noqa: E402


class ChatCompletionStub:
    def __init__(self, status_code, response_body):
        self.status_code = status_code
        self.response_body = response_body.encode("utf-8")
        self.authorization = None
        self.organization = None
        self.project = None
        self.path = None
        self.request_body = None
        self.server = None
        self.thread = None

    def start(self):
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - stdlib callback name
                length = int(self.headers.get("Content-Length") or "0")
                stub.path = self.path
                stub.authorization = self.headers.get("Authorization")
                stub.organization = self.headers.get("OpenAI-Organization")
                stub.project = self.headers.get("OpenAI-Project")
                stub.request_body = self.rfile.read(length).decode("utf-8")
                self.send_response(stub.status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(stub.response_body)))
                self.end_headers()
                self.wfile.write(stub.response_body)

            def log_message(self, format, *args):  # noqa: A002 - stdlib signature
                return

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self

    def stop(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
        if self.thread is not None:
            self.thread.join(timeout=2)

    @property
    def base_url(self):
        assert self.server is not None
        return f"http://127.0.0.1:{self.server.server_address[1]}/v1"


class WorkerModelClientTest(unittest.TestCase):
    def test_disabled_mode_skips_model_call(self):
        client = WorkerModelClient(WorkerModelClientSettings(mode="disabled"))

        result = client.generate_text("plan_task", {"task": {"title": "新增分页接口"}})

        self.assertIsNone(result)

    def test_fixture_mode_returns_auditable_model_result(self):
        client = WorkerModelClient(
            WorkerModelClientSettings(
                mode="fixture",
                provider="WORKER_FIXTURE",
                model="worker-fixture-plan-v1",
                fixture_response="先定位 UserController，再补 Service 和测试。",
            )
        )

        result = client.generate_text("plan_task", {"task": {"title": "新增分页接口"}})

        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(result.provider, "WORKER_FIXTURE")
        self.assertEqual(result.model, "worker-fixture-plan-v1")
        self.assertEqual(result.text, "先定位 UserController，再补 Service 和测试。")
        self.assertEqual(result.response["mode"], "fixture")
        self.assertEqual(result.response["stepName"], "plan_task")
        self.assertGreaterEqual(result.duration_ms, 0)

    def test_fixture_mode_requires_response_text(self):
        client = WorkerModelClient(WorkerModelClientSettings(mode="fixture", fixture_response=""))

        with self.assertRaisesRegex(ValueError, "REPOPILOT_WORKER_MODEL_FIXTURE_RESPONSE"):
            client.generate_text("plan_task", {"task": {"title": "新增分页接口"}})

    def test_openai_compatible_mode_calls_chat_completions_endpoint(self):
        stub = ChatCompletionStub(
            200,
            """
            {
              "model": "gpt-worker-planner-test",
              "usage": {
                "prompt_tokens": 31,
                "completion_tokens": 17,
                "total_tokens": 48
              },
              "choices": [
                {
                  "message": {
                    "content": "优先定位 UserController、UserService 和对应测试，再进入安全预检。"
                  }
                }
              ]
            }
            """,
        ).start()
        try:
            client = WorkerModelClient(
                WorkerModelClientSettings(
                    mode="openai-compatible",
                    model="gpt-worker-planner-test",
                    api_base_url=stub.base_url,
                    api_key="test-worker-key",
                    timeout_seconds=5,
                    max_completion_tokens=888,
                    instruction_role="developer",
                    organization="org-test",
                    project="proj-test",
                )
            )

            result = client.generate_text("plan_task", {"task": {"title": "新增 User 分页接口"}})

            self.assertIsNotNone(result)
            assert result is not None
            self.assertEqual(result.provider, "OPENAI_COMPATIBLE")
            self.assertEqual(result.model, "gpt-worker-planner-test")
            self.assertIn("UserController", result.text)
            self.assertEqual(result.prompt_tokens, 31)
            self.assertEqual(result.completion_tokens, 17)
            self.assertEqual(result.total_tokens, 48)
            self.assertEqual(stub.path, "/v1/chat/completions")
            self.assertEqual(stub.authorization, "Bearer test-worker-key")
            self.assertEqual(stub.organization, "org-test")
            self.assertEqual(stub.project, "proj-test")
            self.assertIsNotNone(stub.request_body)
            assert stub.request_body is not None
            self.assertIn('"model": "gpt-worker-planner-test"', stub.request_body)
            self.assertIn('"max_completion_tokens": 888', stub.request_body)
            self.assertIn('"role": "developer"', stub.request_body)
            self.assertIn("RepoPilot PlannerAgent", stub.request_body)
            self.assertIn("新增 User 分页接口", stub.request_body)
            self.assertNotIn("test-worker-key", str(result.prompt))
            self.assertNotIn("Authorization", str(result.prompt))
        finally:
            stub.stop()

    def test_openai_mode_rejects_missing_api_key(self):
        client = WorkerModelClient(
            WorkerModelClientSettings(mode="openai-compatible", model="gpt-worker-planner-test")
        )

        with self.assertRaisesRegex(ValueError, "REPOPILOT_WORKER_MODEL_API_KEY"):
            client.generate_text("plan_task", {"task": {"title": "新增分页接口"}})

    def test_openai_mode_rejects_missing_model(self):
        client = WorkerModelClient(
            WorkerModelClientSettings(mode="openai-compatible", api_key="test-worker-key", model="")
        )

        with self.assertRaisesRegex(ValueError, "REPOPILOT_WORKER_MODEL_NAME"):
            client.generate_text("plan_task", {"task": {"title": "新增分页接口"}})

    def test_openai_mode_rejects_http_failure(self):
        stub = ChatCompletionStub(429, '{"error":{"message":"rate limited"}}').start()
        try:
            client = WorkerModelClient(
                WorkerModelClientSettings(
                    mode="openai",
                    model="gpt-worker-planner-test",
                    api_base_url=stub.base_url,
                    api_key="test-worker-key",
                    timeout_seconds=5,
                )
            )

            with self.assertRaisesRegex(ValueError, "HTTP 429"):
                client.generate_text("plan_task", {"task": {"title": "新增分页接口"}})
        finally:
            stub.stop()


if __name__ == "__main__":
    unittest.main()
