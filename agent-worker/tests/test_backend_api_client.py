import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.clients.backend_api import BackendApiClient, BackendApiError  # noqa: E402
from app.schemas import AgentStepRecordRequest  # noqa: E402


class BackendApiStub:
    def __init__(self):
        self.responses = {}
        self.request_counts = {}
        self.request_bodies = []
        self.server = None
        self.thread = None

    def set_responses(self, path, responses):
        self.responses[path] = list(responses)
        return self

    def start(self):
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - stdlib callback name
                stub.respond(self)

            def do_POST(self):  # noqa: N802 - stdlib callback name
                length = int(self.headers.get("Content-Length") or "0")
                stub.request_bodies.append(self.rfile.read(length).decode("utf-8"))
                stub.respond(self)

            def log_message(self, format, *args):  # noqa: A002 - stdlib signature
                return

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self

    def respond(self, handler):
        path = handler.path
        self.request_counts[path] = self.request_counts.get(path, 0) + 1
        choices = self.responses.get(path)
        if not choices:
            status_code, response_body = 404, '{"success":false,"code":"NOT_FOUND"}'
        else:
            index = min(self.request_counts[path] - 1, len(choices) - 1)
            status_code, response_body = choices[index]
        body = response_body.encode("utf-8")
        handler.send_response(status_code)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(body)))
        handler.end_headers()
        handler.wfile.write(body)

    def count(self, path):
        return self.request_counts.get(path, 0)

    def stop(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
        if self.thread is not None:
            self.thread.join(timeout=2)

    @property
    def base_url(self):
        assert self.server is not None
        return f"http://127.0.0.1:{self.server.server_address[1]}"


class BackendApiClientTest(unittest.TestCase):
    def test_read_only_tool_get_retries_retryable_backend_failure(self):
        context_path = "/api/internal/agent-worker/runs/606/context"
        tool_call_path = "/api/internal/agent-worker/runs/606/tool-calls"
        stub = (
            BackendApiStub()
            .set_responses(
                context_path,
                [
                    (503, '{"success":false,"code":"TEMPORARY_BACKEND_FAILURE"}'),
                    (200, '{"success":true,"data":{"runId":606,"taskId":303,"projectId":202}}'),
                ],
            )
            .set_responses(tool_call_path, [(200, '{"success":true,"data":{"id":1}}')])
            .start()
        )
        try:
            client = BackendApiClient(
                base_url=stub.base_url,
                callback_token="test-worker-callback-token",
                retry_max_attempts=2,
                retry_backoff_seconds=0,
            )

            context = client.load_run_context(606)

            self.assertEqual(context["runId"], 606)
            self.assertEqual(stub.count(context_path), 2)
            self.assertEqual(stub.count(tool_call_path), 1)
            self.assertEqual(len(stub.request_bodies), 1)
            recorded_call = json.loads(stub.request_bodies[0])
            self.assertEqual(recorded_call["output"]["retryAttemptCount"], 1)
            self.assertEqual(recorded_call["output"]["retryAttempts"][0]["attempt"], 1)
            self.assertIn("HTTP 503", recorded_call["output"]["retryAttempts"][0]["message"])
        finally:
            stub.stop()

    def test_write_callback_post_does_not_retry_to_avoid_duplicate_side_effects(self):
        step_path = "/api/internal/agent-worker/runs/606/steps"
        stub = (
            BackendApiStub()
            .set_responses(
                step_path,
                [
                    (503, '{"success":false,"code":"TEMPORARY_BACKEND_FAILURE"}'),
                    (200, '{"success":true,"data":{"id":2}}'),
                ],
            )
            .start()
        )
        try:
            client = BackendApiClient(
                base_url=stub.base_url,
                callback_token="test-worker-callback-token",
                retry_max_attempts=3,
                retry_backoff_seconds=0,
            )

            with self.assertRaisesRegex(BackendApiError, "HTTP 503"):
                client.record_step(
                    606,
                    AgentStepRecordRequest(
                        step_name="plan_task",
                        status="SUCCESS",
                    ),
                )

            self.assertEqual(stub.count(step_path), 1)
        finally:
            stub.stop()


if __name__ == "__main__":
    unittest.main()
