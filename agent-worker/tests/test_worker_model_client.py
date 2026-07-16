import sys
import unittest
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.clients.model_client import WorkerModelClient, WorkerModelClientSettings  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
