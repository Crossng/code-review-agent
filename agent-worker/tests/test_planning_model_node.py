import sys
import unittest
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.clients.model_client import WorkerModelResult  # noqa: E402
from app.graph.nodes.planning import plan_task  # noqa: E402
from app.schemas import AgentRunStartRequest  # noqa: E402


class FakeBackend:
    def __init__(self):
        self.search_requests = []
        self.model_calls = []
        self.steps = []

    def search_code(self, run_id, query, limit=4):
        self.search_requests.append({"runId": run_id, "query": query, "limit": limit})
        return {
            "query": query,
            "limit": limit,
            "results": [
                {
                    "chunkId": 11,
                    "filePath": "src/main/java/com/example/demo/user/UserController.java",
                    "symbolName": "UserController",
                }
            ],
        }

    def record_model_call(self, run_id, model_call):
        self.model_calls.append({"runId": run_id, "modelCall": model_call})
        return {"success": True, "data": {"id": 9}}

    def record_step(self, run_id, step):
        self.steps.append({"runId": run_id, "step": step})
        return {"success": True, "data": {"id": 10}}


class FakeModelClient:
    def __init__(self):
        self.prompts = []

    def generate_text(self, step_name, prompt):
        self.prompts.append({"stepName": step_name, "prompt": prompt})
        return WorkerModelResult(
            provider="WORKER_FIXTURE",
            model="worker-fixture-plan-v1",
            prompt=prompt,
            response={"mode": "fixture", "text": "优先修改 UserController 和 UserService。"},
            text="优先修改 UserController 和 UserService。",
            duration_ms=7,
        )


class PlanningModelNodeTest(unittest.TestCase):
    def test_plan_task_records_fixture_model_call_and_keeps_deterministic_plan(self):
        backend = FakeBackend()
        model_client = FakeModelClient()
        request = AgentRunStartRequest(
            task_id=303,
            project_id=202,
            user_request="给 User 模块新增分页查询接口",
            repo_path="/workspace/repos/202/source",
            base_branch="main",
        )
        loaded_context = {
            "context": {
                "taskId": 303,
                "projectId": 202,
                "repoFullName": "demo/repo",
                "taskType": "FEATURE",
                "title": "新增 User 分页接口",
                "description": "需要保持现有 Controller 风格。",
            },
            "loadOutput": {
                "sampleFiles": [
                    {"path": "src/main/java/com/example/demo/user/UserController.java"},
                    {"path": "src/main/java/com/example/demo/user/UserService.java"},
                ]
            },
            "indexStatus": {
                "fileCount": 6,
                "symbolCount": 18,
            },
        }

        output = plan_task(606, loaded_context, request, client=backend, model_client=model_client)

        self.assertGreaterEqual(len(backend.search_requests), 1)
        self.assertEqual(len(model_client.prompts), 1)
        self.assertEqual(model_client.prompts[0]["stepName"], "plan_task")
        self.assertEqual(model_client.prompts[0]["prompt"]["task"]["title"], "新增 User 分页接口")
        self.assertEqual(len(backend.model_calls), 1)
        model_call = backend.model_calls[0]["modelCall"]
        self.assertEqual(model_call.step_name, "plan_task")
        self.assertEqual(model_call.model_provider, "WORKER_FIXTURE")
        self.assertEqual(model_call.model_name, "worker-fixture-plan-v1")
        self.assertEqual(model_call.status, "SUCCESS")
        self.assertEqual(model_call.duration_ms, 7)
        self.assertIn("UserController", output["modelPlanText"])
        self.assertEqual(output["modelProvider"], "WORKER_FIXTURE")
        self.assertGreaterEqual(len(output["steps"]), 5)
        self.assertGreaterEqual(len(output["searchQueries"]), 1)
        self.assertEqual(len(backend.steps), 1)
        step_output = backend.steps[0]["step"].output
        assert step_output is not None
        self.assertIn("UserService", step_output["modelPlanText"])
        self.assertEqual(step_output["testStrategy"], "生成补丁后先执行 diff 安全预检，再在 Docker 沙箱运行 mvn -q test。")


if __name__ == "__main__":
    unittest.main()
