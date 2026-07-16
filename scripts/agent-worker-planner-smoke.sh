#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-planner-smoke"
SUMMARY_JSON="$ARTIFACT_DIR/last-run.json"
LOG_DIR="$ROOT_DIR/target/agent-worker-planner-smoke/logs"

usage() {
  cat <<'EOF'
RepoPilot Agent Worker Planner 模型节点 smoke

用法:
  ./scripts/agent-worker-planner-smoke.sh

说明:
  - 启动本地后端 HTTP stub、本地 OpenAI-compatible Chat Completions stub 和真实 Agent Worker。
  - 配置 REPOPILOT_WORKER_MODEL_MODE=openai-compatible。
  - 调用 /runs/{run_id}/start。
  - 验证 Worker 在 plan_task 内调用 Planner 模型 stub，并把 modelPlanText、provider、model 和 token usage 写入 model call audit。
  - 验证 API key 只进入 Authorization header，不写入 prompt/response 审计证据。
  - 运行证据写入 output/agent-worker-planner-smoke/last-run.json。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

mkdir -p "$ARTIFACT_DIR" "$LOG_DIR"

echo "RepoPilot Agent Worker Planner 模型节点 smoke"

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
planner_api_key = "planner-smoke-key"
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
    "plannerRequests": [],
}


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def api_response(data, trace_id="planner-smoke"):
    return {"success": True, "data": data, "code": None, "message": None, "traceId": trace_id}


class PlannerStubHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        parsed = urlparse(self.path)
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        captured["plannerRequests"].append(
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
        response = {
            "model": "gpt-worker-planner-smoke",
            "usage": {
                "prompt_tokens": 41,
                "completion_tokens": 23,
                "total_tokens": 64,
            },
            "choices": [
                {
                    "message": {
                        "content": "模型计划摘要：优先定位 UserController、UserService 和 UserServiceTest，生成最小分页 diff 后进入安全预检、沙箱测试和人工审批。"
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
                "startedAt": "2026-07-17T00:00:00Z",
                "finishedAt": "2026-07-17T00:00:01Z",
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
                "promptJson": json.dumps(event["body"].get("prompt", {}), ensure_ascii=False),
                "responseJson": json.dumps(event["body"].get("response", {}), ensure_ascii=False),
                "status": event["body"]["status"],
                "promptTokens": event["body"].get("prompt_tokens"),
                "completionTokens": event["body"].get("completion_tokens"),
                "totalTokens": event["body"].get("total_tokens"),
                "durationMs": event["body"].get("duration_ms"),
                "errorMessage": event["body"].get("error_message"),
                "startedAt": "2026-07-17T00:00:00Z",
                "finishedAt": "2026-07-17T00:00:01Z",
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
                "changedFiles": [{"path": ".repopilot/task-303-worker-plan.md", "changeType": "ADDED"}],
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
                "changedPaths": [".repopilot/task-303-worker-plan.md"],
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
                "summary": "没有自动审查发现。",
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
            "inputJson": json.dumps(event["body"].get("input", {}), ensure_ascii=False),
            "outputJson": json.dumps(event["body"].get("output", {}), ensure_ascii=False),
            "errorMessage": event["body"].get("error_message"),
            "startedAt": "2026-07-17T00:00:00Z",
            "finishedAt": "2026-07-17T00:00:01Z",
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


planner_server = HTTPServer(("127.0.0.1", 0), PlannerStubHandler)
planner_thread = threading.Thread(target=planner_server.serve_forever, daemon=True)
planner_thread.start()
backend_server = HTTPServer(("127.0.0.1", 0), BackendStubHandler)
backend_thread = threading.Thread(target=backend_server.serve_forever, daemon=True)
backend_thread.start()

worker_port = free_port()
worker_log_path = log_dir / "agent-worker-planner-smoke.log"
env = os.environ.copy()
env.update(
    {
        "PYTHONPATH": str(root_dir / "agent-worker"),
        "REPOPILOT_BACKEND_BASE_URL": f"http://127.0.0.1:{backend_server.server_port}",
        "REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN": "planner-smoke-token",
        "REPOPILOT_BACKEND_TIMEOUT_SECONDS": "3",
        "REPOPILOT_WORKER_MODEL_MODE": "openai-compatible",
        "REPOPILOT_WORKER_MODEL_API_BASE_URL": f"http://127.0.0.1:{planner_server.server_port}/v1",
        "REPOPILOT_WORKER_MODEL_API_KEY": planner_api_key,
        "REPOPILOT_WORKER_MODEL_NAME": "gpt-worker-planner-smoke",
        "REPOPILOT_WORKER_MODEL_MAX_COMPLETION_TOKENS": "777",
        "REPOPILOT_WORKER_MODEL_TIMEOUT_SECONDS": "3",
        "REPOPILOT_WORKER_MODEL_INSTRUCTION_ROLE": "developer",
        "REPOPILOT_WORKER_MODEL_ORGANIZATION": "org-planner-smoke",
        "REPOPILOT_WORKER_MODEL_PROJECT": "proj-planner-smoke",
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
        or len(captured["modelCalls"]) < 2
        or len(captured["plannerRequests"]) < 1
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
    backend_thread.join(timeout=3)
    planner_server.shutdown()
    planner_thread.join(timeout=3)

if health.get("status") != "UP":
    raise SystemExit(f"worker health mismatch: {health}")
if start.get("run_id") != 606 or start.get("accepted") is not True or start.get("status") != "QUEUED":
    raise SystemExit(f"worker start mismatch: {start}")
if len(captured["steps"]) < 5:
    raise SystemExit(f"expected five worker step callbacks, got {captured['steps']}")
if len(captured["plannerRequests"]) != 1:
    raise SystemExit(f"expected one planner request, got {captured['plannerRequests']}")
if len(captured["modelCalls"]) != 2:
    raise SystemExit(f"expected two model calls, got {captured['modelCalls']}")
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
if any(event["token"] != "planner-smoke-token" for event in captured["backendGets"] + callback_events):
    raise SystemExit("worker internal token header mismatch")

planner_request = captured["plannerRequests"][0]
if planner_request["path"] != "/v1/chat/completions":
    raise SystemExit(f"planner path mismatch: {planner_request}")
if planner_request["authorization"] != f"Bearer {planner_api_key}":
    raise SystemExit(f"planner authorization mismatch: {planner_request}")
if planner_request["organization"] != "org-planner-smoke" or planner_request["project"] != "proj-planner-smoke":
    raise SystemExit(f"planner optional headers mismatch: {planner_request}")
planner_body = planner_request["body"]
if planner_body.get("model") != "gpt-worker-planner-smoke":
    raise SystemExit(f"planner model mismatch: {planner_body}")
if planner_body.get("max_completion_tokens") != 777:
    raise SystemExit(f"planner max tokens mismatch: {planner_body}")
messages = planner_body.get("messages", [])
if len(messages) != 2:
    raise SystemExit(f"planner messages mismatch: {planner_body}")
if messages[0].get("role") != "developer" or "RepoPilot PlannerAgent" not in messages[0].get("content", ""):
    raise SystemExit(f"planner system prompt mismatch: {planner_body}")
if "给 User 模块新增分页查询接口" not in messages[1].get("content", ""):
    raise SystemExit(f"planner user prompt missing task: {planner_body}")
if planner_api_key in json.dumps(planner_body, ensure_ascii=False):
    raise SystemExit("planner request body leaked API key")

step_by_name = {event["body"]["step_name"]: event["body"] for event in captured["steps"]}
if set(step_by_name) != {"load_task_context", "ensure_index", "plan_task", "retrieve_context", "generate_patch"}:
    raise SystemExit(f"step names mismatch: {list(step_by_name)}")
plan_output = step_by_name["plan_task"].get("output", {})
if "模型计划摘要" not in plan_output.get("modelPlanText", ""):
    raise SystemExit(f"plan_task modelPlanText mismatch: {plan_output}")
if plan_output.get("modelProvider") != "OPENAI_COMPATIBLE":
    raise SystemExit(f"plan_task model provider mismatch: {plan_output}")
if plan_output.get("modelName") != "gpt-worker-planner-smoke":
    raise SystemExit(f"plan_task model name mismatch: {plan_output}")
if "searchQueries" not in plan_output or len(plan_output.get("steps", [])) < 5:
    raise SystemExit(f"plan_task deterministic output missing: {plan_output}")

model_by_step = {event["body"]["step_name"]: event["body"] for event in captured["modelCalls"]}
if set(model_by_step) != {"plan_task", "generate_patch"}:
    raise SystemExit(f"model call steps mismatch: {model_by_step}")
plan_model = model_by_step["plan_task"]
if plan_model.get("model_provider") != "OPENAI_COMPATIBLE":
    raise SystemExit(f"plan_task model provider mismatch: {plan_model}")
if plan_model.get("model_name") != "gpt-worker-planner-smoke":
    raise SystemExit(f"plan_task model name mismatch: {plan_model}")
if plan_model.get("prompt_tokens") != 41 or plan_model.get("completion_tokens") != 23:
    raise SystemExit(f"plan_task token usage mismatch: {plan_model}")
if plan_model.get("total_tokens") != 64:
    raise SystemExit(f"plan_task total tokens mismatch: {plan_model}")
serialized_plan_model = json.dumps(plan_model, ensure_ascii=False)
if planner_api_key in serialized_plan_model or "Authorization" in serialized_plan_model:
    raise SystemExit(f"plan_task model audit leaked credential material: {plan_model}")
if "模型计划摘要" not in serialized_plan_model:
    raise SystemExit(f"plan_task model audit missing assistant content: {plan_model}")
patch_model = model_by_step["generate_patch"]
if patch_model.get("model_provider") != "AGENT_WORKER":
    raise SystemExit(f"generate_patch model call mismatch: {patch_model}")

patches = captured["patches"]
if len(patches) != 1 or patches[0]["body"].get("generation_mode") != "WORKER_SAFE_PLANNING_DRAFT":
    raise SystemExit(f"patch callback mismatch: {patches}")
if len(captured["safetyChecks"]) != 1 or len(captured["sandboxRuns"]) != 1:
    raise SystemExit("post-patch safety/sandbox callbacks missing")
if len(captured["reviews"]) != 1 or len(captured["approvalReady"]) != 1:
    raise SystemExit("review/approval-ready callbacks missing")

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "worker": {
        "port": worker_port,
        "health": health,
        "start": start,
    },
    "plannerRequest": {
        "path": planner_request["path"],
        "authorizationHeaderPresent": planner_request["authorization"] == f"Bearer {planner_api_key}",
        "organizationHeader": planner_request["organization"],
        "projectHeader": planner_request["project"],
        "model": planner_body["model"],
        "maxCompletionTokens": planner_body["max_completion_tokens"],
        "messageRoles": [message["role"] for message in messages],
        "apiKeyInRequestBody": planner_api_key in json.dumps(planner_body, ensure_ascii=False),
    },
    "steps": [
        {
            "stepName": event["body"]["step_name"],
            "status": event["body"]["status"],
            "tokenHeaderPresent": event["token"] == "planner-smoke-token",
        }
        for event in captured["steps"]
    ],
    "modelCalls": [
        {
            "stepName": event["body"]["step_name"],
            "modelProvider": event["body"]["model_provider"],
            "modelName": event["body"]["model_name"],
            "status": event["body"]["status"],
            "promptTokens": event["body"].get("prompt_tokens"),
            "completionTokens": event["body"].get("completion_tokens"),
            "totalTokens": event["body"].get("total_tokens"),
            "tokenHeaderPresent": event["token"] == "planner-smoke-token",
            "apiKeyInAudit": planner_api_key in json.dumps(event["body"], ensure_ascii=False),
            "authorizationInAudit": "Authorization" in json.dumps(event["body"], ensure_ascii=False),
        }
        for event in captured["modelCalls"]
    ],
    "planTask": {
        "summary": plan_output["summary"],
        "modelProvider": plan_output["modelProvider"],
        "modelName": plan_output["modelName"],
        "modelPlanText": plan_output["modelPlanText"],
        "stepCount": len(plan_output["steps"]),
        "searchQueries": plan_output["searchQueries"],
    },
    "postPatchGates": {
        "patches": len(captured["patches"]),
        "safetyChecks": len(captured["safetyChecks"]),
        "sandboxRuns": len(captured["sandboxRuns"]),
        "reviews": len(captured["reviews"]),
        "approvalReady": len(captured["approvalReady"]),
    },
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker Planner 模型节点验证通过。")
print(f"Planner model: {summary['planTask']['modelProvider']} / {summary['planTask']['modelName']}")
print(f"Model calls: {len(summary['modelCalls'])}; Steps: {len(summary['steps'])}")
print(f"证据文件: {summary_path}")
PY
