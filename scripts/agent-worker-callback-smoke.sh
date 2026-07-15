#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-callback-smoke"
SUMMARY_JSON="$ARTIFACT_DIR/last-run.json"

usage() {
  cat <<'EOF'
RepoPilot Agent Worker 回写 smoke

用法:
  ./scripts/agent-worker-callback-smoke.sh

说明:
  - 启动本地 HTTP stub，验证 Python Agent Worker 的 BackendApiClient。
  - 校验 /api/internal/agent-worker/runs/{run_id}/steps 路径、callback token header 和 step JSON。
  - 校验 /api/internal/agent-worker/runs/{run_id}/tool-calls 路径、callback token header 和 tool call JSON。
  - 校验 /api/internal/agent-worker/runs/{run_id}/model-calls 路径、callback token header 和 model call JSON。
  - 校验 /api/internal/agent-worker/runs/{run_id}/status 路径、callback token header 和 status JSON。
  - 运行证据写入 output/agent-worker-callback-smoke/last-run.json。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

mkdir -p "$ARTIFACT_DIR"

echo "RepoPilot Agent Worker 回写 smoke"

PYTHONPATH="$ROOT_DIR/agent-worker" python3 - "$SUMMARY_JSON" <<'PY'
import json
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from app.clients.backend_api import BackendApiClient
from app.schemas import (
    AgentModelCallRecordRequest,
    AgentStatusUpdateRequest,
    AgentStepRecordRequest,
    AgentToolCallRecordRequest,
)

summary_path = Path(sys.argv[1])
captured = {"steps": [], "statuses": [], "toolCalls": [], "modelCalls": []}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        event = {
            "method": self.command,
            "path": self.path,
            "token": self.headers.get("X-RepoPilot-Worker-Token"),
            "contentType": self.headers.get("Content-Type"),
            "body": json.loads(body),
        }
        if self.path.endswith("/steps"):
            captured["steps"].append(event)
            response_data = {
                "id": 909,
                "stepName": event["body"]["step_name"],
                "status": event["body"]["status"],
                "inputJson": json.dumps(event["body"].get("input", {}), ensure_ascii=False),
                "outputJson": json.dumps(event["body"].get("output", {}), ensure_ascii=False),
                "errorMessage": event["body"].get("error_message"),
                "startedAt": "2026-07-16T00:00:00Z",
                "finishedAt": "2026-07-16T00:00:01Z",
            }
        elif self.path.endswith("/tool-calls"):
            captured["toolCalls"].append(event)
            response_data = {
                "id": 910,
                "agentRunId": 404,
                "toolName": event["body"]["tool_name"],
                "inputJson": json.dumps(event["body"].get("input", {}), ensure_ascii=False),
                "outputJson": json.dumps(event["body"].get("output", {}), ensure_ascii=False),
                "status": event["body"]["status"],
                "durationMs": event["body"].get("duration_ms"),
                "errorMessage": event["body"].get("error_message"),
                "startedAt": "2026-07-16T00:00:00Z",
                "finishedAt": "2026-07-16T00:00:01Z",
            }
        elif self.path.endswith("/model-calls"):
            captured["modelCalls"].append(event)
            response_data = {
                "id": 911,
                "agentRunId": 404,
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
        elif self.path.endswith("/status"):
            captured["statuses"].append(event)
            response_data = {
                "taskId": 101,
                "taskStatus": event["body"].get("task_status"),
                "runId": 404,
                "runStatus": event["body"].get("run_status"),
                "streamCompleted": event["body"].get("complete_stream", False),
            }
        else:
            self.send_response(404)
            self.end_headers()
            return
        response = {
            "success": True,
            "data": response_data,
            "code": None,
            "message": None,
            "traceId": "callback-smoke",
        }
        encoded = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


server = HTTPServer(("127.0.0.1", 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    client = BackendApiClient(
        base_url=f"http://127.0.0.1:{server.server_port}",
        callback_token="callback-smoke-token",
        timeout_seconds=3,
    )
    step_response = client.record_step(
        404,
        AgentStepRecordRequest(
            step_name="worker_callback_smoke",
            status="SUCCESS",
            input={"runId": 404, "source": "agent-worker"},
            output={"summary": "Worker callback client smoke passed", "graph_nodes": ["plan_task"]},
        ),
    )
    tool_call_response = client.record_tool_call(
        404,
        AgentToolCallRecordRequest(
            tool_name="read_project_file",
            status="SUCCESS",
            input={"path": "src/main/java/com/example/UserController.java", "authorization": "Bearer should-redact"},
            output={"path": "src/main/java/com/example/UserController.java", "size": 2048},
            duration_ms=12,
        ),
    )
    model_call_response = client.record_model_call(
        404,
        AgentModelCallRecordRequest(
            step_name="generate_patch",
            model_provider="OPENAI_COMPATIBLE",
            model_name="gpt-test-coder",
            status="SUCCESS",
            prompt={"instruction": "Generate a safe patch", "api_key": "should-redact"},
            response={"summary": "Generated draft patch"},
            prompt_tokens=12,
            completion_tokens=8,
            total_tokens=20,
            duration_ms=33,
        ),
    )
    status_response = client.update_status(
        404,
        AgentStatusUpdateRequest(
            task_status="WAITING_HUMAN_APPROVAL",
            run_status="SUCCESS",
            stream_message="Worker callback smoke completed",
            complete_stream=True,
        ),
    )
finally:
    server.shutdown()
    thread.join(timeout=3)

if (
    len(captured["steps"]) != 1
    or len(captured["toolCalls"]) != 1
    or len(captured["modelCalls"]) != 1
    or len(captured["statuses"]) != 1
):
    raise SystemExit(f"callback count mismatch: {captured}")
step_event = captured["steps"][0]
tool_event = captured["toolCalls"][0]
model_event = captured["modelCalls"][0]
status_event = captured["statuses"][0]
if any(event.get("method") != "POST" for event in [step_event, tool_event, model_event, status_event]):
    raise SystemExit(f"method mismatch: {captured}")
if step_event.get("path") != "/api/internal/agent-worker/runs/404/steps":
    raise SystemExit(f"step path mismatch: {step_event}")
if tool_event.get("path") != "/api/internal/agent-worker/runs/404/tool-calls":
    raise SystemExit(f"tool call path mismatch: {tool_event}")
if model_event.get("path") != "/api/internal/agent-worker/runs/404/model-calls":
    raise SystemExit(f"model call path mismatch: {model_event}")
if status_event.get("path") != "/api/internal/agent-worker/runs/404/status":
    raise SystemExit(f"status path mismatch: {status_event}")
if any(event.get("token") != "callback-smoke-token" for event in [step_event, tool_event, model_event, status_event]):
    raise SystemExit("callback token header mismatch")
if any(event.get("contentType") != "application/json" for event in [step_event, tool_event, model_event, status_event]):
    raise SystemExit("content-type mismatch")
step_body = step_event.get("body", {})
tool_body = tool_event.get("body", {})
model_body = model_event.get("body", {})
status_body = status_event.get("body", {})
if step_body.get("step_name") != "worker_callback_smoke" or step_body.get("status") != "SUCCESS":
    raise SystemExit(f"step body contract mismatch: {step_body}")
if step_body.get("input", {}).get("source") != "agent-worker":
    raise SystemExit(f"step input contract mismatch: {step_body}")
if tool_body.get("tool_name") != "read_project_file" or tool_body.get("status") != "SUCCESS":
    raise SystemExit(f"tool call body contract mismatch: {tool_body}")
if tool_body.get("duration_ms") != 12 or "authorization" not in tool_body.get("input", {}):
    raise SystemExit(f"tool call timing/input mismatch: {tool_body}")
if model_body.get("step_name") != "generate_patch" or model_body.get("status") != "SUCCESS":
    raise SystemExit(f"model call body contract mismatch: {model_body}")
if model_body.get("model_provider") != "OPENAI_COMPATIBLE" or model_body.get("model_name") != "gpt-test-coder":
    raise SystemExit(f"model call metadata mismatch: {model_body}")
if model_body.get("prompt_tokens") != 12 or model_body.get("completion_tokens") != 8 or model_body.get("total_tokens") != 20:
    raise SystemExit(f"model call token mismatch: {model_body}")
if status_body.get("task_status") != "WAITING_HUMAN_APPROVAL" or status_body.get("run_status") != "SUCCESS":
    raise SystemExit(f"status body contract mismatch: {status_body}")
if status_body.get("complete_stream") is not True:
    raise SystemExit(f"status complete_stream mismatch: {status_body}")
if step_response.get("data", {}).get("stepName") != "worker_callback_smoke":
    raise SystemExit(f"step response parse mismatch: {step_response}")
if tool_call_response.get("data", {}).get("toolName") != "read_project_file":
    raise SystemExit(f"tool call response parse mismatch: {tool_call_response}")
if model_call_response.get("data", {}).get("stepName") != "generate_patch":
    raise SystemExit(f"model call response parse mismatch: {model_call_response}")
if status_response.get("data", {}).get("taskStatus") != "WAITING_HUMAN_APPROVAL":
    raise SystemExit(f"status response parse mismatch: {status_response}")

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "stepRequest": {
        "method": step_event["method"],
        "path": step_event["path"],
        "tokenHeaderPresent": step_event["token"] == "callback-smoke-token",
        "stepName": step_body["step_name"],
        "status": step_body["status"],
    },
    "toolCallRequest": {
        "method": tool_event["method"],
        "path": tool_event["path"],
        "tokenHeaderPresent": tool_event["token"] == "callback-smoke-token",
        "toolName": tool_body["tool_name"],
        "status": tool_body["status"],
        "durationMs": tool_body["duration_ms"],
    },
    "modelCallRequest": {
        "method": model_event["method"],
        "path": model_event["path"],
        "tokenHeaderPresent": model_event["token"] == "callback-smoke-token",
        "stepName": model_body["step_name"],
        "modelProvider": model_body["model_provider"],
        "modelName": model_body["model_name"],
        "status": model_body["status"],
        "totalTokens": model_body["total_tokens"],
    },
    "statusRequest": {
        "method": status_event["method"],
        "path": status_event["path"],
        "tokenHeaderPresent": status_event["token"] == "callback-smoke-token",
        "taskStatus": status_body["task_status"],
        "runStatus": status_body["run_status"],
        "completeStream": status_body["complete_stream"],
    },
    "stepResponse": step_response["data"],
    "toolCallResponse": tool_call_response["data"],
    "modelCallResponse": model_call_response["data"],
    "statusResponse": status_response["data"],
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker 回写 client 验证通过。")
print(f"Step: {step_body['step_name']} -> {step_response['data']['status']}")
print(f"Tool call: {tool_body['tool_name']} -> {tool_call_response['data']['status']}")
print(f"Model call: {model_body['step_name']} / {model_body['model_name']} -> {model_call_response['data']['status']}")
print(f"Status: {status_body['task_status']} / {status_body['run_status']}")
print(f"证据文件: {summary_path}")
PY
