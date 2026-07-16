import sys
import unittest
from pathlib import Path


AGENT_WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(AGENT_WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(AGENT_WORKER_ROOT))

from app.clients.model_client import WorkerModelResult  # noqa: E402
from app.graph.nodes.patch import (  # noqa: E402
    CODER_PATCH_GENERATION_MODE,
    PATCH_GENERATION_MODE,
    generate_patch,
    parse_coder_patch_output,
)
from app.schemas import AgentRunStartRequest  # noqa: E402


class FakeBackend:
    def __init__(self):
        self.model_calls = []
        self.patches = []
        self.steps = []
        self.safety_checks = []
        self.sandbox_runs = []
        self.reviews = []
        self.approval_ready = []

    def record_model_call(self, run_id, model_call):
        self.model_calls.append({"runId": run_id, "modelCall": model_call})
        return {"success": True, "data": {"id": 840 + len(self.model_calls)}}

    def record_patch(self, run_id, patch):
        self.patches.append({"runId": run_id, "patch": patch})
        return {
            "success": True,
            "data": {
                "id": 880 + len(self.patches),
                "status": "GENERATED",
                "baseBranch": patch.base_branch or "main",
                "targetBranch": patch.target_branch or "repopilot/task-303",
                "changedFiles": [{"path": first_changed_path(patch.diff_content), "changeType": "ADDED"}],
            },
        }

    def record_step(self, run_id, step):
        self.steps.append({"runId": run_id, "step": step})
        return {"success": True, "data": {"id": 900 + len(self.steps)}}

    def validate_patch_safety(self, run_id, patch_id):
        self.safety_checks.append({"runId": run_id, "patchId": patch_id})
        return {"success": True, "data": {"safe": True, "stepStatus": "SUCCESS"}}

    def run_patch_sandbox_tests(self, run_id, patch_id):
        self.sandbox_runs.append({"runId": run_id, "patchId": patch_id})
        return {"success": True, "data": {"testsPassed": True, "testStatus": "PASSED"}}

    def review_patch(self, run_id, patch_id):
        self.reviews.append({"runId": run_id, "patchId": patch_id})
        return {"success": True, "data": {"stepStatus": "SUCCESS", "riskLevel": "NONE"}}

    def mark_patch_ready_for_approval(self, run_id, patch_id):
        self.approval_ready.append({"runId": run_id, "patchId": patch_id})
        return {"success": True, "data": {"taskStatus": "WAITING_HUMAN_APPROVAL", "runStatus": "SUCCESS"}}


class DisabledModelClient:
    def generate_text(self, step_name, prompt):
        self.prompt = prompt
        return None


class FixtureCoderModelClient:
    def __init__(self, text):
        self.text = text
        self.prompts = []

    def generate_text(self, step_name, prompt):
        self.prompts.append({"stepName": step_name, "prompt": prompt})
        return WorkerModelResult(
            provider="WORKER_CODER_FIXTURE",
            model="worker-fixture-coder-v1",
            prompt=prompt,
            response={"mode": "fixture"},
            text=self.text,
            duration_ms=7,
            prompt_tokens=11,
            completion_tokens=13,
            total_tokens=24,
        )


class PatchModelNodeTest(unittest.TestCase):
    def test_parse_coder_patch_output_accepts_raw_unified_diff(self):
        parsed = parse_coder_patch_output(coder_diff())

        self.assertEqual(parsed["format"], "raw_diff")
        self.assertEqual(parsed["changedPaths"], [".repopilot/worker-coder-note.md"])
        self.assertTrue(parsed["diffContent"].endswith("\n"))

    def test_parse_coder_patch_output_rejects_prose_around_diff(self):
        with self.assertRaisesRegex(ValueError, "prose outside the diff block"):
            parse_coder_patch_output(f"说明\n```diff\n{coder_diff()}```")

    def test_generate_patch_keeps_deterministic_draft_when_worker_coder_disabled(self):
        backend = FakeBackend()

        output = generate_patch(
            606,
            loaded_context(),
            index_status(),
            plan_output(),
            retrieval_output(),
            request(),
            client=backend,
            model_client=DisabledModelClient(),
        )

        self.assertEqual(output["generationMode"], PATCH_GENERATION_MODE)
        self.assertEqual(backend.model_calls[0]["modelCall"].model_provider, "AGENT_WORKER")
        self.assertEqual(backend.patches[0]["patch"].generation_mode, PATCH_GENERATION_MODE)
        self.assertTrue(backend.safety_checks)
        self.assertTrue(backend.approval_ready)

    def test_generate_patch_records_llm_coder_draft_from_fixture_diff(self):
        backend = FakeBackend()
        model_client = FixtureCoderModelClient(coder_diff())

        output = generate_patch(
            606,
            loaded_context(),
            index_status(),
            plan_output(),
            retrieval_output(),
            request(),
            client=backend,
            model_client=model_client,
        )

        self.assertEqual(output["generationMode"], CODER_PATCH_GENERATION_MODE)
        self.assertEqual(output["generationProvider"], "WORKER_CODER_FIXTURE")
        self.assertEqual(output["generationModel"], "worker-fixture-coder-v1")
        self.assertEqual(output["diffPath"], ".repopilot/worker-coder-note.md")
        self.assertEqual(output["evidence"]["modelOutputFormat"], "raw_diff")
        self.assertEqual(output["evidence"]["changedPaths"], [".repopilot/worker-coder-note.md"])
        self.assertEqual(backend.patches[0]["patch"].generation_mode, CODER_PATCH_GENERATION_MODE)
        self.assertIn("# Worker Coder Fixture", backend.patches[0]["patch"].diff_content)
        model_call = backend.model_calls[0]["modelCall"]
        self.assertEqual(model_call.model_provider, "WORKER_CODER_FIXTURE")
        self.assertEqual(model_call.prompt_tokens, 11)
        self.assertEqual(model_call.completion_tokens, 13)
        self.assertEqual(model_call.total_tokens, 24)
        self.assertEqual(model_call.response["parsedDiff"]["changedPaths"], [".repopilot/worker-coder-note.md"])
        self.assertEqual(model_client.prompts[0]["stepName"], "generate_patch")
        self.assertEqual(model_client.prompts[0]["prompt"]["role"], "CoderAgent")


def first_changed_path(diff_content):
    for line in diff_content.splitlines():
        if line.startswith("diff --git "):
            return line.split(" b/", 1)[1]
    return ".repopilot/unknown.md"


def request():
    return AgentRunStartRequest(
        task_id=303,
        project_id=202,
        user_request="给 User 模块新增分页查询接口",
        repo_path="/workspace/repos/202/source",
        base_branch="main",
    )


def loaded_context():
    return {
        "context": {
            "taskId": 303,
            "projectId": 202,
            "repoFullName": "demo/repo",
            "defaultBranch": "main",
            "taskType": "FEATURE",
            "title": "新增 User 分页接口",
            "description": "需要保持现有 Controller 风格。",
        }
    }


def index_status():
    return {
        "indexReady": True,
        "javaFileCount": 6,
        "symbolCount": 18,
        "controllerCount": 1,
        "serviceCount": 1,
    }


def plan_output():
    return {
        "summary": "新增 User 分页查询接口",
        "steps": [{"order": 1, "title": "定位 Controller", "reason": "保持接口风格一致"}],
        "testStrategy": "运行 mvn -q test",
    }


def retrieval_output():
    return {
        "queries": ["UserController", "UserService"],
        "uniqueResultCount": 1,
        "results": [
            {
                "chunkId": 21,
                "filePath": "src/main/java/com/example/demo/user/UserController.java",
                "chunkType": "SYMBOL",
                "symbolName": "UserController",
                "summary": "User API Controller",
                "preview": "class UserController {}",
            }
        ],
        "readFiles": [
            {
                "path": "src/main/java/com/example/demo/user/UserController.java",
                "size": 120,
                "content": "class UserController {}",
            }
        ],
    }


def coder_diff():
    return "\n".join(
        [
            "diff --git a/.repopilot/worker-coder-note.md b/.repopilot/worker-coder-note.md",
            "new file mode 100644",
            "index 0000000..1111111",
            "--- /dev/null",
            "+++ b/.repopilot/worker-coder-note.md",
            "@@ -0,0 +1,2 @@",
            "+# Worker Coder Fixture",
            "+这是 Worker Coder fixture 生成的安全 diff。",
            "",
        ]
    )


if __name__ == "__main__":
    unittest.main()
