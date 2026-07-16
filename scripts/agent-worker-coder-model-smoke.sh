#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-coder-model-smoke"
SUMMARY_JSON="$ARTIFACT_DIR/last-run.json"
LOG_DIR="$ROOT_DIR/target/agent-worker-coder-model-smoke/logs"

usage() {
  cat <<'EOF'
RepoPilot Agent Worker Coder 模型节点 smoke

用法:
  ./scripts/agent-worker-coder-model-smoke.sh

说明:
  - 启动本地后端 HTTP stub、本地 OpenAI-compatible Chat Completions stub 和真实 Agent Worker。
  - 配置 REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible。
  - 调用 /runs/{run_id}/start。
  - 验证 Worker 在 generate_patch 内调用 Coder 模型 stub，并把 raw unified diff 解析为 LLM_CODER_DRAFT。
  - 验证模型 diff 仍进入 safety、sandbox、review 和 approval-ready 后置门。
  - 验证 API key 只进入 Authorization header，不写入 prompt/response 审计证据。
  - 运行证据写入 output/agent-worker-coder-model-smoke/last-run.json。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

mkdir -p "$ARTIFACT_DIR" "$LOG_DIR"

echo "RepoPilot Agent Worker Coder 模型节点 smoke"

PYTHONPATH="$ROOT_DIR/agent-worker" python3 - "$ROOT_DIR" "$SUMMARY_JSON" "$LOG_DIR" <<'PY'
import json
import os
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

root_dir = Path(sys.argv[1])
summary_path = Path(sys.argv[2])
log_dir = Path(sys.argv[3])
coder_api_key = "coder-model-smoke-key"
coder_changed_path = ".repopilot/worker-coder-model-smoke.md"
captured = {
    "backendGets": [],
    "steps": [],
    "toolCalls": [],
    "modelCalls": [],
    "patches": [],
    "safetyChecks": [],
    "sandboxRuns": [],
    "reviews": [],
    "approvalReady": [],
    "coderRequests": [],
    "transientFailures": [],
}


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def api_response(data, trace_id="coder-model-smoke"):
    return {"success": True, "data": data, "code": None, "message": None, "traceId": trace_id}


def coder_diff() -> str:
    return "\n".join(
        [
            f"diff --git a/{coder_changed_path} b/{coder_changed_path}",
            "new file mode 100644",
            "index 0000000..1111111",
            "--- /dev/null",
            f"+++ b/{coder_changed_path}",
            "@@ -0,0 +1,4 @@",
            "+# Worker Coder Model Smoke",
            "+这是 OpenAI-compatible Coder stub 生成的安全 unified diff。",
            "+它必须继续经过安全预检、沙箱测试、风险审查和人工审批。",
            "+模型输出本身不会直接创建 PR。",
            "",
        ]
    )


class CoderModelStubHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        parsed = urlparse(self.path)
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        captured["coderRequests"].append(
            {
                "path": parsed.path,
                "authorization": self.headers.get("Authorization"),
                "organization": self.headers.get("OpenAI-Organization"),
                "project": self.headers.get("OpenAI-Project"),
                "body": json.loads(body),
            }
        )
        if parsed.path != "/v1/chat/completions":
            self.send_response(404)
            self.end_headers()
            return
        if len(captured["coderRequests"]) == 1:
            captured["transientFailures"].append(
                {
                    "kind": "coder_model",
                    "status": 429,
                    "path": parsed.path,
                    "recoveredByRetry": True,
                }
            )
            response = {"error": {"message": "coder smoke transient rate limit"}}
            encoded = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(429)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        response = {
            "model": "gpt-worker-coder-smoke",
            "usage": {
                "prompt_tokens": 67,
                "completion_tokens": 31,
                "total_tokens": 98,
            },
            "choices": [
                {
                    "message": {
                        "content": coder_diff()
                    }
                }
            ],
        }
        encoded = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


class BackendStubHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        captured["backendGets"].append(
            {
                "method": self.command,
                "path": parsed.path,
                "query": query,
                "token": self.headers.get("X-RepoPilot-Worker-Token"),
            }
        )
        if parsed.path.endswith("/context"):
            data = {
                "runId": 606,
                "runStatus": "RUNNING",
                "taskId": 303,
                "taskStatus": "CREATED",
                "taskType": "FEATURE",
                "title": "给 User 模块新增分页查询接口",
                "description": "需要读取 UserController 并生成最小可审查 diff。",
                "projectId": 202,
                "repoUrl": "file:///demo",
                "repoFullName": "demo/repo",
                "defaultBranch": "main",
                "localPath": "/workspace/repos/202/source",
                "projectStatus": "READY",
            }
        elif parsed.path.endswith("/project/files"):
            data = [
                {"path": "src/main/java/com/example/demo/user/UserController.java", "type": "FILE", "size": 1800},
                {"path": "src/main/java/com/example/demo/user/UserService.java", "type": "FILE", "size": 2200},
                {"path": "src/main/java/com/example/demo/user/UserMapper.java", "type": "FILE", "size": 900},
                {"path": "src/test/java/com/example/demo/user/UserServiceTest.java", "type": "FILE", "size": 1300},
            ]
        elif parsed.path.endswith("/project/symbols"):
            data = [
                {
                    "id": 12,
                    "filePath": "src/main/java/com/example/demo/user/UserController.java",
                    "symbolType": "CONTROLLER",
                    "name": "UserController",
                    "qualifiedName": "com.example.demo.user.UserController",
                    "annotations": "RestController",
                    "startLine": 1,
                    "endLine": 36,
                },
                {
                    "id": 13,
                    "filePath": "src/main/java/com/example/demo/user/UserService.java",
                    "symbolType": "SERVICE",
                    "name": "UserService",
                    "qualifiedName": "com.example.demo.user.UserService",
                    "annotations": "Service",
                    "startLine": 1,
                    "endLine": 52,
                },
            ]
        elif parsed.path.endswith("/project/search"):
            query_text = query.get("query", [""])[0]
            data = {
                "query": query_text,
                "limit": int(query.get("limit", ["4"])[0]),
                "results": [
                    {
                        "chunkId": 77,
                        "filePath": "src/main/java/com/example/demo/user/UserController.java",
                        "chunkType": "SYMBOL",
                        "symbolType": "CONTROLLER",
                        "symbolName": "UserController",
                        "qualifiedName": "com.example.demo.user.UserController",
                        "startLine": 1,
                        "endLine": 36,
                        "summary": "User Controller",
                        "preview": f"Controller context for {query_text}",
                    },
                    {
                        "chunkId": 78,
                        "filePath": "src/main/java/com/example/demo/user/UserService.java",
                        "chunkType": "SYMBOL",
                        "symbolType": "SERVICE",
                        "symbolName": "UserService",
                        "qualifiedName": "com.example.demo.user.UserService",
                        "startLine": 1,
                        "endLine": 52,
                        "summary": "User Service",
                        "preview": f"Service context for {query_text}",
                    },
                ],
            }
        elif parsed.path.endswith("/project/file"):
            file_path = query.get("path", [""])[0]
            contents = {
                "src/main/java/com/example/demo/user/UserController.java": """
package com.example.demo.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserController {
    private final UserService userService;

    @GetMapping("/users")
    Object listUsers() {
        return userService.listUsers();
    }
}
""".strip(),
                "src/main/java/com/example/demo/user/UserService.java": """
package com.example.demo.user;

import org.springframework.stereotype.Service;

@Service
class UserService {
    Object listUsers() {
        return java.util.List.of();
    }
}
""".strip(),
            }
            if file_path not in contents:
                self.send_response(404)
                self.end_headers()
                return
            content = contents[file_path]
            data = {
                "path": file_path,
                "size": len(content.encode("utf-8")),
                "content": content,
            }
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.respond(api_response(data))

    def do_POST(self):
        parsed = urlparse(self.path)
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        event = {
            "method": self.command,
            "path": parsed.path,
            "token": self.headers.get("X-RepoPilot-Worker-Token"),
            "contentType": self.headers.get("Content-Type"),
            "body": json.loads(body) if body else {},
        }
        if parsed.path.endswith("/tool-calls"):
            captured["toolCalls"].append(event)
            data = {
                "id": 800 + len(captured["toolCalls"]),
                "agentRunId": 606,
                "toolName": event["body"]["tool_name"],
                "status": event["body"]["status"],
                "durationMs": event["body"].get("duration_ms", 0),
                "errorMessage": event["body"].get("error_message"),
            }
            self.respond(api_response(data))
            return
        if parsed.path.endswith("/model-calls"):
            captured["modelCalls"].append(event)
            data = {
                "id": 840 + len(captured["modelCalls"]),
                "agentRunId": 606,
                "stepName": event["body"]["step_name"],
                "modelProvider": event["body"]["model_provider"],
                "modelName": event["body"]["model_name"],
                "status": event["body"]["status"],
                "promptTokens": event["body"].get("prompt_tokens"),
                "completionTokens": event["body"].get("completion_tokens"),
                "totalTokens": event["body"].get("total_tokens"),
                "durationMs": event["body"].get("duration_ms"),
                "errorMessage": event["body"].get("error_message"),
            }
            self.respond(api_response(data))
            return
        if parsed.path.endswith("/patches"):
            captured["patches"].append(event)
            data = {
                "id": 880 + len(captured["patches"]),
                "agentTaskId": 303,
                "agentRunId": 606,
                "baseBranch": event["body"].get("base_branch") or "main",
                "targetBranch": event["body"].get("target_branch") or "repopilot/task-303",
                "diffContent": event["body"]["diff_content"],
                "summary": event["body"].get("summary"),
                "generationMode": event["body"]["generation_mode"],
                "generationProvider": event["body"]["generation_provider"],
                "generationModel": event["body"].get("generation_model"),
                "changedFiles": [{"path": coder_changed_path, "changeType": "ADDED"}],
                "status": "GENERATED",
                "createdAt": "2026-07-17T00:00:01Z",
            }
            self.respond(api_response(data))
            return
        if parsed.path.endswith("/safety"):
            captured["safetyChecks"].append(event)
            data = {
                "patchId": 881,
                "agentTaskId": 303,
                "agentRunId": 606,
                "safe": True,
                "changedPaths": [coder_changed_path],
                "findings": [],
                "stepId": 990,
                "stepStatus": "SUCCESS",
            }
            self.respond(api_response(data))
            return
        if parsed.path.endswith("/sandbox-tests"):
            captured["sandboxRuns"].append(event)
            data = {
                "patchId": 881,
                "agentTaskId": 303,
                "agentRunId": 606,
                "patchStatus": "APPLIED",
                "applied": True,
                "testsPassed": True,
                "applyStepId": 991,
                "applyStepStatus": "SUCCESS",
                "testStepId": 992,
                "testStepStatus": "SUCCESS",
                "testRunId": 993,
                "testStatus": "PASSED",
            }
            self.respond(api_response(data))
            return
        if parsed.path.endswith("/review"):
            captured["reviews"].append(event)
            data = {
                "patchId": 881,
                "agentTaskId": 303,
                "agentRunId": 606,
                "testRunId": 993,
                "riskLevel": "NONE",
                "summary": "Worker Coder 模型 diff smoke 没有自动审查发现。",
                "findings": [],
                "stepId": 994,
                "stepStatus": "SUCCESS",
            }
            self.respond(api_response(data))
            return
        if parsed.path.endswith("/approval-ready"):
            captured["approvalReady"].append(event)
            data = {
                "patchId": 881,
                "agentTaskId": 303,
                "agentRunId": 606,
                "testRunId": 993,
                "reviewStepId": 994,
                "approvalStepId": 995,
                "approvalStepStatus": "PENDING",
                "taskStatus": "WAITING_HUMAN_APPROVAL",
                "runStatus": "SUCCESS",
                "streamCompleted": True,
            }
            self.respond(api_response(data))
            return
        if not parsed.path.endswith("/steps"):
            self.send_response(404)
            self.end_headers()
            return
        captured["steps"].append(event)
        data = {
            "id": 900 + len(captured["steps"]),
            "stepName": event["body"]["step_name"],
            "status": event["body"]["status"],
            "errorMessage": event["body"].get("error_message"),
        }
        self.respond(api_response(data))

    def respond(self, payload):
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


backend_server = HTTPServer(("127.0.0.1", 0), BackendStubHandler)
backend_thread = threading.Thread(target=backend_server.serve_forever, daemon=True)
backend_thread.start()
coder_server = HTTPServer(("127.0.0.1", 0), CoderModelStubHandler)
coder_thread = threading.Thread(target=coder_server.serve_forever, daemon=True)
coder_thread.start()

worker_port = free_port()
worker_log_path = log_dir / "agent-worker-coder-model-smoke.log"
env = os.environ.copy()
env.update(
    {
        "PYTHONPATH": str(root_dir / "agent-worker"),
        "REPOPILOT_BACKEND_BASE_URL": f"http://127.0.0.1:{backend_server.server_port}",
        "REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN": "coder-model-smoke-token",
        "REPOPILOT_BACKEND_TIMEOUT_SECONDS": "3",
        "REPOPILOT_WORKER_MODEL_MODE": "disabled",
        "REPOPILOT_WORKER_CODER_MODEL_MODE": "openai-compatible",
        "REPOPILOT_WORKER_CODER_MODEL_API_BASE_URL": f"http://127.0.0.1:{coder_server.server_port}/v1",
        "REPOPILOT_WORKER_CODER_MODEL_API_KEY": coder_api_key,
        "REPOPILOT_WORKER_CODER_MODEL_NAME": "gpt-worker-coder-smoke",
        "REPOPILOT_WORKER_CODER_MODEL_MAX_COMPLETION_TOKENS": "777",
        "REPOPILOT_WORKER_CODER_MODEL_ORGANIZATION": "org-worker-coder-smoke",
        "REPOPILOT_WORKER_CODER_MODEL_PROJECT": "proj-worker-coder-smoke",
        "REPOPILOT_WORKER_RETRY_MAX_ATTEMPTS": "3",
        "REPOPILOT_WORKER_RETRY_BACKOFF_SECONDS": "0",
    }
)
worker_log = worker_log_path.open("w", encoding="utf-8")
worker = subprocess.Popen(
    [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(worker_port)],
    cwd=root_dir / "agent-worker",
    env=env,
    stdout=worker_log,
    stderr=subprocess.STDOUT,
)


def request_json(method: str, url: str, payload=None) -> dict:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {} if payload is None else {"Content-Type": "application/json"}
    request = Request(url, data=body, method=method, headers=headers)
    with urlopen(request, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


try:
    health_url = f"http://127.0.0.1:{worker_port}/health"
    deadline = time.time() + 30
    while True:
        try:
            health = request_json("GET", health_url)
            break
        except URLError:
            if time.time() > deadline:
                raise
            time.sleep(0.25)

    start = request_json(
        "POST",
        f"http://127.0.0.1:{worker_port}/runs/606/start",
        {
            "task_id": 303,
            "project_id": 202,
            "user_request": "给 User 模块新增分页查询接口",
            "repo_path": "/workspace/repos/202/source",
            "base_branch": "main",
        },
    )

    deadline = time.time() + 10
    while (
        len(captured["steps"]) < 5
        or len(captured["coderRequests"]) < 2
        or len(captured["patches"]) < 1
        or len(captured["safetyChecks"]) < 1
        or len(captured["sandboxRuns"]) < 1
        or len(captured["reviews"]) < 1
        or len(captured["approvalReady"]) < 1
    ) and time.time() < deadline:
        time.sleep(0.1)
finally:
    worker.terminate()
    try:
        worker.wait(timeout=5)
    except subprocess.TimeoutExpired:
        worker.kill()
        worker.wait(timeout=5)
    worker_log.close()
    backend_server.shutdown()
    coder_server.shutdown()
    backend_thread.join(timeout=3)
    coder_thread.join(timeout=3)
    backend_server.server_close()
    coder_server.server_close()

if health.get("status") != "UP":
    raise SystemExit(f"worker health mismatch: {health}")
if start.get("run_id") != 606 or start.get("accepted") is not True or start.get("status") != "QUEUED":
    raise SystemExit(f"worker start mismatch: {start}")
if len(captured["steps"]) < 5:
    raise SystemExit(f"expected five worker step callbacks, got {captured['steps']}")
if len(captured["coderRequests"]) != 2:
    raise SystemExit(f"expected two coder requests after one retry, got {captured['coderRequests']}")
if captured["transientFailures"] != [
    {
        "kind": "coder_model",
        "status": 429,
        "path": "/v1/chat/completions",
        "recoveredByRetry": True,
    }
]:
    raise SystemExit(f"coder retry evidence mismatch: {captured['transientFailures']}")
if len(captured["patches"]) != 1:
    raise SystemExit(f"expected one patch callback, got {captured['patches']}")
if len(captured["safetyChecks"]) != 1 or len(captured["sandboxRuns"]) != 1:
    raise SystemExit("post-patch safety or sandbox gate did not run")
if len(captured["reviews"]) != 1 or len(captured["approvalReady"]) != 1:
    raise SystemExit("post-patch review or approval-ready gate did not run")

callback_events = (
    captured["steps"]
    + captured["toolCalls"]
    + captured["modelCalls"]
    + captured["patches"]
    + captured["safetyChecks"]
    + captured["sandboxRuns"]
    + captured["reviews"]
    + captured["approvalReady"]
)
if any(event["token"] != "coder-model-smoke-token" for event in captured["backendGets"] + callback_events):
    raise SystemExit("worker internal token header mismatch")

step_by_name = {event["body"]["step_name"]: event["body"] for event in captured["steps"]}
if set(step_by_name) != {"load_task_context", "ensure_index", "plan_task", "retrieve_context", "generate_patch"}:
    raise SystemExit(f"step names mismatch: {list(step_by_name)}")
for name, step in step_by_name.items():
    if step.get("status") != "SUCCESS":
        raise SystemExit(f"{name} status mismatch: {step}")

coder_request = captured["coderRequests"][-1]
coder_body = coder_request["body"]
if coder_request.get("authorization") != f"Bearer {coder_api_key}":
    raise SystemExit(f"coder Authorization header mismatch: {coder_request}")
if coder_request.get("organization") != "org-worker-coder-smoke":
    raise SystemExit(f"coder organization header mismatch: {coder_request}")
if coder_request.get("project") != "proj-worker-coder-smoke":
    raise SystemExit(f"coder project header mismatch: {coder_request}")
if coder_body.get("model") != "gpt-worker-coder-smoke":
    raise SystemExit(f"coder model mismatch: {coder_body}")
if coder_body.get("max_completion_tokens") != 777:
    raise SystemExit(f"coder max_completion_tokens mismatch: {coder_body}")
serialized_coder_body = json.dumps(coder_body, ensure_ascii=False)
for expected in ["RepoPilot CoderAgent", "Return only one raw unified diff", "CoderAgent", "UserController"]:
    if expected not in serialized_coder_body:
        raise SystemExit(f"coder request missing {expected}: {serialized_coder_body}")
if "RepoPilot PlannerAgent" in serialized_coder_body:
    raise SystemExit(f"coder request used Planner prompt: {serialized_coder_body}")

model_by_step = {event["body"]["step_name"]: event["body"] for event in captured["modelCalls"]}
if set(model_by_step) != {"generate_patch"}:
    raise SystemExit(f"model call steps mismatch: {model_by_step}")
patch_model = model_by_step["generate_patch"]
if patch_model.get("model_provider") != "OPENAI_COMPATIBLE":
    raise SystemExit(f"generate_patch provider mismatch: {patch_model}")
if patch_model.get("model_name") != "gpt-worker-coder-smoke":
    raise SystemExit(f"generate_patch model mismatch: {patch_model}")
if patch_model.get("prompt_tokens") != 67 or patch_model.get("completion_tokens") != 31:
    raise SystemExit(f"generate_patch token usage mismatch: {patch_model}")
if patch_model.get("total_tokens") != 98:
    raise SystemExit(f"generate_patch total tokens mismatch: {patch_model}")
serialized_patch_model = json.dumps(patch_model, ensure_ascii=False)
if coder_api_key in serialized_patch_model or "Authorization" in serialized_patch_model:
    raise SystemExit(f"generate_patch model audit leaked credential material: {patch_model}")
if coder_changed_path not in serialized_patch_model:
    raise SystemExit(f"generate_patch model audit missing changed path: {patch_model}")
patch_retry_response = patch_model.get("response", {})
if patch_retry_response.get("retryAttemptCount") != 1:
    raise SystemExit(f"generate_patch model retry audit missing retryAttemptCount: {patch_model}")
if "HTTP 429" not in patch_retry_response.get("retryAttempts", [{}])[0].get("message", ""):
    raise SystemExit(f"generate_patch model retry audit missing HTTP 429 message: {patch_model}")

patch_body = captured["patches"][0]["body"]
if patch_body.get("generation_mode") != "LLM_CODER_DRAFT":
    raise SystemExit(f"patch generation mode mismatch: {patch_body}")
if patch_body.get("generation_provider") != "OPENAI_COMPATIBLE":
    raise SystemExit(f"patch generation provider mismatch: {patch_body}")
if patch_body.get("generation_model") != "gpt-worker-coder-smoke":
    raise SystemExit(f"patch generation model mismatch: {patch_body}")
if coder_changed_path not in patch_body.get("diff_content", ""):
    raise SystemExit(f"patch diff missing changed path: {patch_body}")
if "```" in patch_body.get("diff_content", ""):
    raise SystemExit(f"patch diff contains Markdown fence: {patch_body}")

patch_output = step_by_name["generate_patch"].get("output", {})
if patch_output.get("generationMode") != "LLM_CODER_DRAFT":
    raise SystemExit(f"generate_patch output mode mismatch: {patch_output}")
if patch_output.get("generationProvider") != "OPENAI_COMPATIBLE":
    raise SystemExit(f"generate_patch output provider mismatch: {patch_output}")
if patch_output.get("diffPath") != coder_changed_path:
    raise SystemExit(f"generate_patch diff path mismatch: {patch_output}")
if patch_output.get("evidence", {}).get("modelOutputFormat") != "raw_diff":
    raise SystemExit(f"generate_patch evidence mismatch: {patch_output}")
if coder_changed_path not in patch_output.get("evidence", {}).get("changedPaths", []):
    raise SystemExit(f"generate_patch changed paths mismatch: {patch_output}")
approval_body = captured["approvalReady"][0]["body"]
if approval_body != {}:
    raise SystemExit(f"approval-ready request body mismatch: {approval_body}")

summary = {
    "health": health,
    "start": start,
    "coderRequest": {
        "path": coder_request["path"],
        "authorizationPresent": bool(coder_request.get("authorization")),
        "organization": coder_request.get("organization"),
        "project": coder_request.get("project"),
        "model": coder_body.get("model"),
        "maxCompletionTokens": coder_body.get("max_completion_tokens"),
    },
    "retryEvidence": {
        "transientFailures": captured["transientFailures"],
        "coderRequestCount": len(captured["coderRequests"]),
        "coderAuditRetryAttemptCount": patch_retry_response.get("retryAttemptCount"),
        "maxAttempts": 3,
    },
    "steps": [
        {
            "stepName": event["body"]["step_name"],
            "status": event["body"]["status"],
            "output": event["body"].get("output"),
        }
        for event in captured["steps"]
    ],
    "modelCalls": [
        {
            "stepName": event["body"]["step_name"],
            "modelProvider": event["body"]["model_provider"],
            "modelName": event["body"]["model_name"],
            "promptTokens": event["body"].get("prompt_tokens"),
            "completionTokens": event["body"].get("completion_tokens"),
            "totalTokens": event["body"].get("total_tokens"),
            "retryAttemptCount": event["body"].get("response", {}).get("retryAttemptCount", 0),
        }
        for event in captured["modelCalls"]
    ],
    "patch": {
        "generationMode": patch_body.get("generation_mode"),
        "generationProvider": patch_body.get("generation_provider"),
        "generationModel": patch_body.get("generation_model"),
        "changedPath": coder_changed_path,
    },
    "postPatchGates": {
        "safetyChecks": len(captured["safetyChecks"]),
        "sandboxRuns": len(captured["sandboxRuns"]),
        "reviews": len(captured["reviews"]),
        "approvalReady": len(captured["approvalReady"]),
    },
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

print("Agent Worker Coder 模型节点验证通过。")
print("Coder model: OPENAI_COMPATIBLE / gpt-worker-coder-smoke")
print(f"Patch: LLM_CODER_DRAFT / {coder_changed_path}")
print(f"证据文件: {summary_path}")
PY
