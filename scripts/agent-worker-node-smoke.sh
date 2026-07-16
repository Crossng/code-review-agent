#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-node-smoke"
SUMMARY_JSON="$ARTIFACT_DIR/last-run.json"
LOG_DIR="$ROOT_DIR/target/agent-worker-node-smoke/logs"

usage() {
  cat <<'EOF'
RepoPilot Agent Worker 初始、检索、补丁草稿、安全预检与沙箱测试节点 smoke

用法:
  ./scripts/agent-worker-node-smoke.sh

说明:
  - 启动本地后端 HTTP stub 和真实 Agent Worker。
  - 调用 /runs/{run_id}/start。
  - 验证 Worker 后台执行 load_task_context、ensure_index、plan_task、retrieve_context 和 generate_patch。
  - 校验 Worker 拉取 context/files/symbols/search/file，自动回写 tool call audit，回写 model call audit 和 patch draft，并回写五个 SUCCESS step。
  - 校验 Worker 生成 patch draft 后调用后端 diff 安全预检接口，并在通过后调用沙箱测试接口。
  - 运行证据写入 output/agent-worker-node-smoke/last-run.json。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

mkdir -p "$ARTIFACT_DIR" "$LOG_DIR"

echo "RepoPilot Agent Worker 初始、检索、补丁草稿、安全预检与沙箱测试节点 smoke"

PYTHONPATH="$ROOT_DIR/agent-worker" python3 - "$ROOT_DIR" "$SUMMARY_JSON" "$LOG_DIR" <<'PY'
import json
import os
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

root_dir = Path(sys.argv[1])
summary_path = Path(sys.argv[2])
log_dir = Path(sys.argv[3])
captured = {
    "gets": [],
    "steps": [],
    "toolCalls": [],
    "modelCalls": [],
    "patches": [],
    "safetyChecks": [],
    "sandboxRuns": [],
}


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class BackendStubHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        event = {
            "method": self.command,
            "path": parsed.path,
            "query": query,
            "token": self.headers.get("X-RepoPilot-Worker-Token"),
        }
        captured["gets"].append(event)
        if parsed.path.endswith("/context"):
            data = {
                "runId": 606,
                "runStatus": "RUNNING",
                "taskId": 303,
                "taskStatus": "CREATED",
                "taskType": "FEATURE",
                "title": "给 User 模块新增分页查询接口",
                "description": "需要读取 UserController 并生成分页查询计划。",
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
                    }
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
        self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})

    def do_POST(self):
        parsed = urlparse(self.path)
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        event = {
            "method": self.command,
            "path": parsed.path,
            "token": self.headers.get("X-RepoPilot-Worker-Token"),
            "contentType": self.headers.get("Content-Type"),
            "body": json.loads(body),
        }
        if parsed.path.endswith("/tool-calls"):
            captured["toolCalls"].append(event)
            data = {
                "id": 800 + len(captured["toolCalls"]),
                "agentRunId": 606,
                "toolName": event["body"]["tool_name"],
                "inputJson": json.dumps(event["body"].get("input", {}), ensure_ascii=False),
                "outputJson": json.dumps(event["body"].get("output", {}), ensure_ascii=False),
                "status": event["body"]["status"],
                "durationMs": event["body"].get("duration_ms", 0),
                "errorMessage": event["body"].get("error_message"),
                "startedAt": "2026-07-16T00:00:00Z",
                "finishedAt": "2026-07-16T00:00:01Z",
            }
            self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})
            return
        if parsed.path.endswith("/model-calls"):
            captured["modelCalls"].append(event)
            data = {
                "id": 840 + len(captured["modelCalls"]),
                "agentRunId": 606,
                "stepName": event["body"]["step_name"],
                "modelProvider": event["body"]["model_provider"],
                "modelName": event["body"]["model_name"],
                "promptJson": json.dumps(event["body"].get("prompt", {}), ensure_ascii=False),
                "responseJson": json.dumps(event["body"].get("response", {}), ensure_ascii=False),
                "status": event["body"]["status"],
                "promptTokens": event["body"].get("prompt_tokens"),
                "completionTokens": event["body"].get("completion_tokens"),
                "totalTokens": event["body"].get("total_tokens"),
                "durationMs": event["body"].get("duration_ms"),
                "errorMessage": event["body"].get("error_message"),
                "startedAt": "2026-07-16T00:00:00Z",
                "finishedAt": "2026-07-16T00:00:01Z",
            }
            self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})
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
                "changedFiles": [{"path": ".repopilot/task-303-worker-plan.md", "changeType": "ADDED"}],
                "status": "GENERATED",
                "createdAt": "2026-07-16T00:00:01Z",
            }
            self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})
            return
        if parsed.path.endswith("/safety"):
            captured["safetyChecks"].append(event)
            data = {
                "patchId": 881,
                "agentTaskId": 303,
                "agentRunId": 606,
                "safe": True,
                "changedPaths": [".repopilot/task-303-worker-plan.md"],
                "findings": [],
                "stepId": 990,
                "stepStatus": "SUCCESS",
            }
            self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})
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
                "applyResult": {
                    "command": "docker run --rm ... git apply ../patch.diff",
                    "exitCode": 0,
                    "timedOut": False,
                    "durationMs": 25,
                    "logExcerpt": "",
                    "logPath": "/workspace/runs/606/patch-apply.log",
                },
                "testStepId": 992,
                "testStepStatus": "SUCCESS",
                "testRunId": 993,
                "testStatus": "PASSED",
                "testRun": {
                    "testRunId": 993,
                    "patchId": 881,
                    "status": "PASSED",
                    "command": "docker run --rm ... mvn -q test",
                    "exitCode": 0,
                    "durationMs": 120,
                    "logExcerpt": "",
                },
            }
            self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})
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
            "inputJson": json.dumps(event["body"].get("input", {}), ensure_ascii=False),
            "outputJson": json.dumps(event["body"].get("output", {}), ensure_ascii=False),
            "errorMessage": event["body"].get("error_message"),
            "startedAt": "2026-07-16T00:00:00Z",
            "finishedAt": "2026-07-16T00:00:01Z",
        }
        self.respond({"success": True, "data": data, "code": None, "message": None, "traceId": "node-smoke"})

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

worker_port = free_port()
worker_log_path = log_dir / "agent-worker-node-smoke.log"
env = os.environ.copy()
env.update(
    {
        "PYTHONPATH": str(root_dir / "agent-worker"),
        "REPOPILOT_BACKEND_BASE_URL": f"http://127.0.0.1:{backend_server.server_port}",
        "REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN": "node-smoke-token",
        "REPOPILOT_BACKEND_TIMEOUT_SECONDS": "3",
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
        or len(captured["safetyChecks"]) < 1
        or len(captured["sandboxRuns"]) < 1
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
    backend_thread.join(timeout=3)

if health.get("status") != "UP":
    raise SystemExit(f"worker health mismatch: {health}")
if start.get("run_id") != 606 or start.get("accepted") is not True or start.get("status") != "QUEUED":
    raise SystemExit(f"worker start mismatch: {start}")
if len(captured["steps"]) < 5:
    raise SystemExit(f"expected five worker step callbacks, got {captured['steps']}")
if len(captured["safetyChecks"]) != 1:
    raise SystemExit(f"expected one worker safety check callback, got {captured['safetyChecks']}")
if len(captured["sandboxRuns"]) != 1:
    raise SystemExit(f"expected one worker sandbox test callback, got {captured['sandboxRuns']}")
callback_events = (
    captured["steps"]
    + captured["toolCalls"]
    + captured["modelCalls"]
    + captured["patches"]
    + captured["safetyChecks"]
    + captured["sandboxRuns"]
)
if any(event["token"] != "node-smoke-token" for event in captured["gets"] + callback_events):
    raise SystemExit("worker internal token header mismatch")

get_paths = [event["path"] for event in captured["gets"]]
for expected in [
    "/api/internal/agent-worker/runs/606/context",
    "/api/internal/agent-worker/runs/606/project/files",
    "/api/internal/agent-worker/runs/606/project/symbols",
    "/api/internal/agent-worker/runs/606/project/search",
    "/api/internal/agent-worker/runs/606/project/file",
]:
    if expected not in get_paths:
        raise SystemExit(f"missing backend tool request {expected}: {get_paths}")

step_by_name = {event["body"]["step_name"]: event["body"] for event in captured["steps"]}
if set(step_by_name) != {"load_task_context", "ensure_index", "plan_task", "retrieve_context", "generate_patch"}:
    raise SystemExit(f"step names mismatch: {list(step_by_name)}")
if step_by_name["load_task_context"].get("status") != "SUCCESS":
    raise SystemExit(f"load_task_context status mismatch: {step_by_name['load_task_context']}")
if step_by_name["ensure_index"].get("status") != "SUCCESS":
    raise SystemExit(f"ensure_index status mismatch: {step_by_name['ensure_index']}")
if step_by_name["plan_task"].get("status") != "SUCCESS":
    raise SystemExit(f"plan_task status mismatch: {step_by_name['plan_task']}")
if step_by_name["retrieve_context"].get("status") != "SUCCESS":
    raise SystemExit(f"retrieve_context status mismatch: {step_by_name['retrieve_context']}")
if step_by_name["generate_patch"].get("status") != "SUCCESS":
    raise SystemExit(f"generate_patch status mismatch: {step_by_name['generate_patch']}")

load_output = step_by_name["load_task_context"].get("output", {})
ensure_output = step_by_name["ensure_index"].get("output", {})
plan_output = step_by_name["plan_task"].get("output", {})
retrieve_output = step_by_name["retrieve_context"].get("output", {})
patch_output = step_by_name["generate_patch"].get("output", {})
if load_output.get("repoFullName") != "demo/repo" or load_output.get("fileCount") != 4:
    raise SystemExit(f"load_task_context output mismatch: {load_output}")
if ensure_output.get("indexReady") is not True or ensure_output.get("javaFileCount") != 4:
    raise SystemExit(f"ensure_index output mismatch: {ensure_output}")
if ensure_output.get("controllerCount") != 1 or ensure_output.get("serviceCount") != 1:
    raise SystemExit(f"ensure_index symbol summary mismatch: {ensure_output}")
if "searchQueries" not in plan_output or len(plan_output.get("steps", [])) < 5:
    raise SystemExit(f"plan_task output mismatch: {plan_output}")
if retrieve_output.get("uniqueResultCount") != 2 or len(retrieve_output.get("readFiles", [])) != 2:
    raise SystemExit(f"retrieve_context output mismatch: {retrieve_output}")
if "给 User 模块新增分页查询接口" not in retrieve_output.get("queries", []):
    raise SystemExit(f"retrieve_context queries mismatch: {retrieve_output}")
if not any(file.get("path", "").endswith("UserController.java") for file in retrieve_output.get("readFiles", [])):
    raise SystemExit(f"retrieve_context readFiles mismatch: {retrieve_output}")
if patch_output.get("generationMode") != "WORKER_SAFE_PLANNING_DRAFT":
    raise SystemExit(f"generate_patch generation mode mismatch: {patch_output}")
if patch_output.get("generationProvider") != "AGENT_WORKER":
    raise SystemExit(f"generate_patch provider mismatch: {patch_output}")
if patch_output.get("generationModel") != "worker-retrieval-plan-v1":
    raise SystemExit(f"generate_patch model mismatch: {patch_output}")
if patch_output.get("patchId") != 881 or patch_output.get("patchStatus") != "GENERATED":
    raise SystemExit(f"generate_patch patch response mismatch: {patch_output}")
if patch_output.get("diffPath") != ".repopilot/task-303-worker-plan.md":
    raise SystemExit(f"generate_patch diff path mismatch: {patch_output}")

tool_calls = captured["toolCalls"]
if len(tool_calls) != len(captured["gets"]):
    raise SystemExit(f"tool call audit count mismatch: gets={len(captured['gets'])}, toolCalls={len(tool_calls)}")
if any(event.get("contentType") != "application/json" for event in tool_calls):
    raise SystemExit(f"tool call content type mismatch: {tool_calls}")
if any(event["body"].get("status") != "SUCCESS" for event in tool_calls):
    raise SystemExit(f"tool call status mismatch: {tool_calls}")
tool_names = [event["body"]["tool_name"] for event in tool_calls]
for expected in ["load_run_context", "list_project_files", "list_symbols", "search_code", "read_project_file"]:
    if expected not in tool_names:
        raise SystemExit(f"missing tool call audit {expected}: {tool_names}")
read_file_tool_calls = [event for event in tool_calls if event["body"]["tool_name"] == "read_project_file"]
if not read_file_tool_calls:
    raise SystemExit(f"missing read_project_file tool audit: {tool_calls}")
if any("contentPreview" not in event["body"].get("output", {}) for event in read_file_tool_calls):
    raise SystemExit(f"read_project_file output summary mismatch: {read_file_tool_calls}")

model_calls = captured["modelCalls"]
if len(model_calls) != 1:
    raise SystemExit(f"model call count mismatch: {model_calls}")
model_body = model_calls[0]["body"]
if model_calls[0].get("contentType") != "application/json":
    raise SystemExit(f"model call content type mismatch: {model_calls}")
if model_body.get("step_name") != "generate_patch" or model_body.get("status") != "SUCCESS":
    raise SystemExit(f"model call contract mismatch: {model_body}")
if model_body.get("model_provider") != "AGENT_WORKER" or model_body.get("model_name") != "worker-retrieval-plan-v1":
    raise SystemExit(f"model call metadata mismatch: {model_body}")

patches = captured["patches"]
if len(patches) != 1:
    raise SystemExit(f"patch callback count mismatch: {patches}")
patch_body = patches[0]["body"]
if patches[0].get("contentType") != "application/json":
    raise SystemExit(f"patch content type mismatch: {patches}")
if patch_body.get("generation_mode") != "WORKER_SAFE_PLANNING_DRAFT":
    raise SystemExit(f"patch generation mode mismatch: {patch_body}")
if patch_body.get("generation_provider") != "AGENT_WORKER":
    raise SystemExit(f"patch generation provider mismatch: {patch_body}")
if patch_body.get("generation_model") != "worker-retrieval-plan-v1":
    raise SystemExit(f"patch generation model mismatch: {patch_body}")
if not patch_body.get("diff_content", "").startswith("diff --git"):
    raise SystemExit(f"patch diff contract mismatch: {patch_body}")
if ".repopilot/task-303-worker-plan.md" not in patch_body.get("diff_content", ""):
    raise SystemExit(f"patch diff path mismatch: {patch_body}")

safety_checks = captured["safetyChecks"]
safety_event = safety_checks[0]
if safety_event.get("contentType") != "application/json":
    raise SystemExit(f"safety check content type mismatch: {safety_checks}")
if safety_event.get("body") != {}:
    raise SystemExit(f"safety check body mismatch: {safety_event}")
if safety_event.get("path") != "/api/internal/agent-worker/runs/606/patches/881/safety":
    raise SystemExit(f"safety check path mismatch: {safety_event}")

sandbox_runs = captured["sandboxRuns"]
sandbox_event = sandbox_runs[0]
if sandbox_event.get("contentType") != "application/json":
    raise SystemExit(f"sandbox test content type mismatch: {sandbox_runs}")
if sandbox_event.get("body") != {}:
    raise SystemExit(f"sandbox test body mismatch: {sandbox_event}")
if sandbox_event.get("path") != "/api/internal/agent-worker/runs/606/patches/881/sandbox-tests":
    raise SystemExit(f"sandbox test path mismatch: {sandbox_event}")

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "worker": {
        "port": worker_port,
        "health": health,
        "start": start,
    },
    "backendRequests": [
        {
            "method": event["method"],
            "path": event["path"],
            "query": event.get("query", {}),
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in captured["gets"]
    ],
    "steps": [
        {
            "path": event["path"],
            "stepName": event["body"]["step_name"],
            "status": event["body"]["status"],
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in captured["steps"]
    ],
    "toolCalls": [
        {
            "path": event["path"],
            "toolName": event["body"]["tool_name"],
            "status": event["body"]["status"],
            "durationMs": event["body"].get("duration_ms"),
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in tool_calls
    ],
    "modelCalls": [
        {
            "path": event["path"],
            "stepName": event["body"]["step_name"],
            "modelProvider": event["body"]["model_provider"],
            "modelName": event["body"]["model_name"],
            "status": event["body"]["status"],
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in model_calls
    ],
    "patches": [
        {
            "path": event["path"],
            "generationMode": event["body"]["generation_mode"],
            "generationProvider": event["body"]["generation_provider"],
            "generationModel": event["body"].get("generation_model"),
            "diffStartsWithGit": event["body"]["diff_content"].startswith("diff --git"),
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in patches
    ],
    "safetyChecks": [
        {
            "path": event["path"],
            "requestBody": event["body"],
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in safety_checks
    ],
    "sandboxRuns": [
        {
            "path": event["path"],
            "requestBody": event["body"],
            "tokenHeaderPresent": event["token"] == "node-smoke-token",
        }
        for event in sandbox_runs
    ],
    "loadTaskContext": {
        "repoFullName": load_output["repoFullName"],
        "fileCount": load_output["fileCount"],
        "symbolCount": load_output["symbolCount"],
    },
    "ensureIndex": {
        "summary": ensure_output["summary"],
        "indexReady": ensure_output["indexReady"],
        "javaFileCount": ensure_output["javaFileCount"],
        "controllerCount": ensure_output["controllerCount"],
        "serviceCount": ensure_output["serviceCount"],
    },
    "planTask": {
        "summary": plan_output["summary"],
        "stepCount": len(plan_output["steps"]),
        "searchQueries": plan_output["searchQueries"],
    },
    "retrieveContext": {
        "summary": retrieve_output["summary"],
        "queries": retrieve_output["queries"],
        "uniqueResultCount": retrieve_output["uniqueResultCount"],
        "readFiles": [file["path"] for file in retrieve_output["readFiles"]],
    },
    "generatePatch": {
        "summary": patch_output["summary"],
        "patchId": patch_output["patchId"],
        "patchStatus": patch_output["patchStatus"],
        "generationMode": patch_output["generationMode"],
        "generationProvider": patch_output["generationProvider"],
        "generationModel": patch_output["generationModel"],
        "diffPath": patch_output["diffPath"],
        "diffLineCount": patch_output["diffLineCount"],
    },
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker 初始、检索、补丁草稿、安全预检与沙箱测试节点验证通过。")
print(f"Steps: {', '.join(step['stepName'] for step in summary['steps'])}")
print(f"Tool calls: {len(summary['toolCalls'])}")
print(
    f"Model calls: {len(summary['modelCalls'])}; "
    f"Patches: {len(summary['patches'])}; "
    f"Safety checks: {len(summary['safetyChecks'])}; "
    f"Sandbox runs: {len(summary['sandboxRuns'])}"
)
print(f"证据文件: {summary_path}")
PY
