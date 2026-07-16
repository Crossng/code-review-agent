import sys
import unittest
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.clients.model_client import WorkerModelResult  # noqa: E402
from app.graph.nodes.planning import plan_task, retrieve_context  # noqa: E402
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

    def read_project_file(self, run_id, path):
        return {"path": path, "size": 120, "content": "class UserServiceTest {}"}


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


class StructuredFakeModelClient:
    def __init__(self):
        self.prompts = []

    def generate_text(self, step_name, prompt):
        self.prompts.append({"stepName": step_name, "prompt": prompt})
        return WorkerModelResult(
            provider="OPENAI_COMPATIBLE",
            model="gpt-worker-planner-smoke",
            prompt=prompt,
            response={"mode": "fixture"},
            text=(
                '{"summary":"模型建议优先补分页 Controller、Service 和测试。",'
                '"steps":[{"order":1,"title":"定位分页入口","reason":"保持接口风格一致",'
                '"expectedFiles":["UserController.java"]}],'
                '"searchQueries":["UserServiceTest pagination","UserMapper limit offset"],'
                '"risks":["分页边界需要测试"],'
                '"testStrategy":"运行 mvn -q test"}'
            ),
            duration_ms=9,
            prompt_tokens=20,
            completion_tokens=10,
            total_tokens=30,
        )


class RetrievalFakeBackend(FakeBackend):
    def search_code(self, run_id, query, limit=4):
        self.search_requests.append({"runId": run_id, "query": query, "limit": limit})
        if "UserServiceTest" in query:
            return {
                "query": query,
                "limit": limit,
                "results": [
                    {
                        "chunkId": 21,
                        "filePath": "src/test/java/com/example/demo/user/UserServiceTest.java",
                        "chunkType": "SYMBOL",
                        "symbolName": "UserServiceTest",
                        "summary": "分页测试",
                        "preview": "class UserServiceTest {}",
                    }
                ],
            }
        return {
            "query": query,
            "limit": limit,
            "results": [
                {
                    "chunkId": 11,
                    "filePath": "src/main/java/com/example/demo/user/UserController.java",
                    "chunkType": "SYMBOL",
                    "symbolName": "UserController",
                    "summary": "Controller",
                    "preview": "class UserController {}",
                }
            ],
        }


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

    def test_plan_task_parses_structured_model_plan_and_records_token_usage(self):
        backend = FakeBackend()
        model_client = StructuredFakeModelClient()
        request = AgentRunStartRequest(
            task_id=303,
            project_id=202,
            user_request="给 User 模块新增分页查询接口",
            repo_path="/workspace/repos/202/source",
            base_branch="main",
        )

        output = plan_task(606, loaded_context(), request, client=backend, model_client=model_client)

        self.assertEqual(output["modelProvider"], "OPENAI_COMPATIBLE")
        self.assertEqual(output["modelName"], "gpt-worker-planner-smoke")
        self.assertIn("分页 Controller", output["modelPlanText"])
        self.assertEqual(output["modelPlan"]["format"], "json_object")
        self.assertEqual(output["modelPlan"]["searchQueries"], ["UserServiceTest pagination", "UserMapper limit offset"])
        self.assertEqual(output["modelPlan"]["steps"][0]["title"], "定位分页入口")
        model_call = backend.model_calls[0]["modelCall"]
        self.assertEqual(model_call.prompt_tokens, 20)
        self.assertEqual(model_call.completion_tokens, 10)
        self.assertEqual(model_call.total_tokens, 30)

    def test_retrieve_context_uses_model_search_queries_after_deterministic_queries(self):
        backend = RetrievalFakeBackend()
        request = AgentRunStartRequest(
            task_id=303,
            project_id=202,
            user_request="给 User 模块新增分页查询接口",
            repo_path="/workspace/repos/202/source",
            base_branch="main",
        )
        plan_output = {
            "searchQueries": ["新增 User 分页接口", "UserController", "UserService", "UserMapper", "pagination"],
            "modelPlan": {
                "searchQueries": ["UserServiceTest pagination", "UserMapper limit offset"],
            },
        }

        output = retrieve_context(606, loaded_context(), plan_output, request, client=backend)

        self.assertEqual(
            output["queries"],
            [
                "新增 User 分页接口",
                "UserController",
                "UserService",
                "UserServiceTest pagination",
                "UserMapper limit offset",
            ],
        )
        self.assertTrue(
            any(file["path"].endswith("UserServiceTest.java") for file in output["readFiles"]),
            output["readFiles"],
        )


def loaded_context():
    return {
        "context": {
            "taskId": 303,
            "projectId": 202,
            "repoFullName": "demo/repo",
            "taskType": "FEATURE",
            "title": "新增 User 分页接口",
            "description": "需要保持现有 Controller 风格。",
        },
        "loadOutput": {
            "fileCount": 6,
            "symbolCount": 18,
            "sampleFiles": [
                {"path": "src/main/java/com/example/demo/user/UserController.java"},
                {"path": "src/main/java/com/example/demo/user/UserService.java"},
            ],
        },
    }


if __name__ == "__main__":
    unittest.main()
