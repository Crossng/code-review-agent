#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/output/agent-worker-coder-smoke"
mkdir -p "$OUTPUT_DIR"

echo "RepoPilot Agent Worker Coder 模型补丁节点 smoke"

PYTHONPATH="$ROOT_DIR/agent-worker" python3 - "$OUTPUT_DIR/last-run.json" <<'PY'
import json
import sys
from pathlib import Path

from app.clients.model_client import WorkerModelResult
from app.graph.nodes.patch import CODER_PATCH_GENERATION_MODE, generate_patch
from app.schemas import AgentRunStartRequest


evidence_path = Path(sys.argv[1])


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
        self.model_calls.append({"runId": run_id, "body": model_call.model_dump(exclude_none=True)})
        return {"success": True, "data": {"id": 840 + len(self.model_calls)}}

    def record_patch(self, run_id, patch):
        body = patch.model_dump(exclude_none=True)
        self.patches.append({"runId": run_id, "body": body})
        return {
            "success": True,
            "data": {
                "id": 880 + len(self.patches),
                "status": "GENERATED",
                "baseBranch": body.get("base_branch") or "main",
                "targetBranch": body.get("target_branch") or "repopilot/task-303",
                "changedFiles": [{"path": ".repopilot/worker-coder-smoke.md", "changeType": "ADDED"}],
            },
        }

    def record_step(self, run_id, step):
        self.steps.append({"runId": run_id, "body": step.model_dump(exclude_none=True)})
        return {"success": True, "data": {"id": 900 + len(self.steps)}}

    def validate_patch_safety(self, run_id, patch_id):
        self.safety_checks.append({"runId": run_id, "patchId": patch_id})
        return {
            "success": True,
            "data": {
                "patchId": patch_id,
                "safe": True,
                "changedPaths": [".repopilot/worker-coder-smoke.md"],
                "findings": [],
                "stepStatus": "SUCCESS",
            },
        }

    def run_patch_sandbox_tests(self, run_id, patch_id):
        self.sandbox_runs.append({"runId": run_id, "patchId": patch_id})
        return {
            "success": True,
            "data": {
                "patchId": patch_id,
                "patchStatus": "APPLIED",
                "applied": True,
                "testsPassed": True,
                "testStatus": "PASSED",
            },
        }

    def review_patch(self, run_id, patch_id):
        self.reviews.append({"runId": run_id, "patchId": patch_id})
        return {
            "success": True,
            "data": {
                "patchId": patch_id,
                "riskLevel": "NONE",
                "summary": "Worker Coder fixture diff smoke 通过。",
                "findings": [],
                "stepStatus": "SUCCESS",
            },
        }

    def mark_patch_ready_for_approval(self, run_id, patch_id):
        self.approval_ready.append({"runId": run_id, "patchId": patch_id})
        return {
            "success": True,
            "data": {
                "patchId": patch_id,
                "taskStatus": "WAITING_HUMAN_APPROVAL",
                "runStatus": "SUCCESS",
            },
        }


class FixtureCoderModelClient:
    def __init__(self):
        self.prompts = []

    def generate_text(self, step_name, prompt):
        self.prompts.append({"stepName": step_name, "prompt": prompt})
        return WorkerModelResult(
            provider="WORKER_CODER_FIXTURE",
            model="worker-fixture-coder-v1",
            prompt=prompt,
            response={"mode": "fixture"},
            text=coder_diff(),
            duration_ms=12,
            prompt_tokens=31,
            completion_tokens=19,
            total_tokens=50,
        )


def coder_diff():
    return "\n".join(
        [
            "diff --git a/.repopilot/worker-coder-smoke.md b/.repopilot/worker-coder-smoke.md",
            "new file mode 100644",
            "index 0000000..1111111",
            "--- /dev/null",
            "+++ b/.repopilot/worker-coder-smoke.md",
            "@@ -0,0 +1,3 @@",
            "+# Worker Coder Smoke",
            "+这是 Worker Coder fixture 生成的安全 unified diff。",
            "+后续仍必须经过安全预检、沙箱测试、风险审查和人工审批。",
            "",
        ]
    )


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
        "defaultBranch": "main",
        "taskType": "FEATURE",
        "title": "新增 User 分页接口",
        "description": "需要保持现有 Controller 风格。",
    }
}
index_status = {
    "indexReady": True,
    "javaFileCount": 6,
    "symbolCount": 18,
    "controllerCount": 1,
    "serviceCount": 1,
}
plan_output = {
    "summary": "新增 User 分页查询接口",
    "steps": [{"order": 1, "title": "定位 Controller", "reason": "保持接口风格一致"}],
    "testStrategy": "运行 mvn -q test",
}
retrieval_output = {
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

backend = FakeBackend()
model_client = FixtureCoderModelClient()
output = generate_patch(
    606,
    loaded_context,
    index_status,
    plan_output,
    retrieval_output,
    request,
    client=backend,
    model_client=model_client,
)

if output.get("generationMode") != CODER_PATCH_GENERATION_MODE:
    raise SystemExit(f"generation mode mismatch: {output}")
if output.get("generationProvider") != "WORKER_CODER_FIXTURE":
    raise SystemExit(f"generation provider mismatch: {output}")
if output.get("diffPath") != ".repopilot/worker-coder-smoke.md":
    raise SystemExit(f"diff path mismatch: {output}")
if output.get("evidence", {}).get("modelOutputFormat") != "raw_diff":
    raise SystemExit(f"model output format mismatch: {output}")
if len(backend.model_calls) != 1 or backend.model_calls[0]["body"].get("model_provider") != "WORKER_CODER_FIXTURE":
    raise SystemExit(f"model call mismatch: {backend.model_calls}")
if len(backend.patches) != 1 or backend.patches[0]["body"].get("generation_mode") != CODER_PATCH_GENERATION_MODE:
    raise SystemExit(f"patch callback mismatch: {backend.patches}")
if not backend.safety_checks or not backend.sandbox_runs or not backend.reviews or not backend.approval_ready:
    raise SystemExit("post-patch gates did not run")

summary = {
    "output": output,
    "modelCalls": backend.model_calls,
    "patches": backend.patches,
    "steps": backend.steps,
    "safetyChecks": backend.safety_checks,
    "sandboxRuns": backend.sandbox_runs,
    "reviews": backend.reviews,
    "approvalReady": backend.approval_ready,
    "promptRole": model_client.prompts[0]["prompt"]["role"],
}
evidence_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

print("Agent Worker Coder 模型补丁节点验证通过。")
print(f"Generation: {output['generationProvider']} / {output['generationModel']} / {output['generationMode']}")
print(f"Changed path: {output['diffPath']}")
print(f"证据文件: {evidence_path}")
PY
