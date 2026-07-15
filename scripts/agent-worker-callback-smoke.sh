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
from app.schemas import AgentStatusUpdateRequest, AgentStepRecordRequest

summary_path = Path(sys.argv[1])
captured = {"steps": [], "statuses": []}


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

if len(captured["steps"]) != 1 or len(captured["statuses"]) != 1:
    raise SystemExit(f"callback count mismatch: {captured}")
step_event = captured["steps"][0]
status_event = captured["statuses"][0]
if step_event.get("method") != "POST" or status_event.get("method") != "POST":
    raise SystemExit(f"method mismatch: {captured}")
if step_event.get("path") != "/api/internal/agent-worker/runs/404/steps":
    raise SystemExit(f"step path mismatch: {step_event}")
if status_event.get("path") != "/api/internal/agent-worker/runs/404/status":
    raise SystemExit(f"status path mismatch: {status_event}")
if step_event.get("token") != "callback-smoke-token" or status_event.get("token") != "callback-smoke-token":
    raise SystemExit("callback token header mismatch")
if step_event.get("contentType") != "application/json" or status_event.get("contentType") != "application/json":
    raise SystemExit("content-type mismatch")
step_body = step_event.get("body", {})
status_body = status_event.get("body", {})
if step_body.get("step_name") != "worker_callback_smoke" or step_body.get("status") != "SUCCESS":
    raise SystemExit(f"step body contract mismatch: {step_body}")
if step_body.get("input", {}).get("source") != "agent-worker":
    raise SystemExit(f"step input contract mismatch: {step_body}")
if status_body.get("task_status") != "WAITING_HUMAN_APPROVAL" or status_body.get("run_status") != "SUCCESS":
    raise SystemExit(f"status body contract mismatch: {status_body}")
if status_body.get("complete_stream") is not True:
    raise SystemExit(f"status complete_stream mismatch: {status_body}")
if step_response.get("data", {}).get("stepName") != "worker_callback_smoke":
    raise SystemExit(f"step response parse mismatch: {step_response}")
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
    "statusRequest": {
        "method": status_event["method"],
        "path": status_event["path"],
        "tokenHeaderPresent": status_event["token"] == "callback-smoke-token",
        "taskStatus": status_body["task_status"],
        "runStatus": status_body["run_status"],
        "completeStream": status_body["complete_stream"],
    },
    "stepResponse": step_response["data"],
    "statusResponse": status_response["data"],
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker 回写 client 验证通过。")
print(f"Step: {step_body['step_name']} -> {step_response['data']['status']}")
print(f"Status: {status_body['task_status']} / {status_body['run_status']}")
print(f"证据文件: {summary_path}")
PY
