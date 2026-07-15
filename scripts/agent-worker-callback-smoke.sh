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
from app.schemas import AgentStepRecordRequest

summary_path = Path(sys.argv[1])
captured = {}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        captured["method"] = self.command
        captured["path"] = self.path
        captured["token"] = self.headers.get("X-RepoPilot-Worker-Token")
        captured["contentType"] = self.headers.get("Content-Type")
        captured["body"] = json.loads(body)
        response = {
            "success": True,
            "data": {
                "id": 909,
                "stepName": captured["body"]["step_name"],
                "status": captured["body"]["status"],
                "inputJson": json.dumps(captured["body"].get("input", {}), ensure_ascii=False),
                "outputJson": json.dumps(captured["body"].get("output", {}), ensure_ascii=False),
                "errorMessage": captured["body"].get("error_message"),
                "startedAt": "2026-07-16T00:00:00Z",
                "finishedAt": "2026-07-16T00:00:01Z",
            },
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
    response = client.record_step(
        404,
        AgentStepRecordRequest(
            step_name="worker_callback_smoke",
            status="SUCCESS",
            input={"runId": 404, "source": "agent-worker"},
            output={"summary": "Worker callback client smoke passed", "graph_nodes": ["plan_task"]},
        ),
    )
finally:
    server.shutdown()
    thread.join(timeout=3)

if captured.get("method") != "POST":
    raise SystemExit(f"method mismatch: {captured}")
if captured.get("path") != "/api/internal/agent-worker/runs/404/steps":
    raise SystemExit(f"path mismatch: {captured}")
if captured.get("token") != "callback-smoke-token":
    raise SystemExit("callback token header mismatch")
if captured.get("contentType") != "application/json":
    raise SystemExit("content-type mismatch")
body = captured.get("body", {})
if body.get("step_name") != "worker_callback_smoke" or body.get("status") != "SUCCESS":
    raise SystemExit(f"body contract mismatch: {body}")
if body.get("input", {}).get("source") != "agent-worker":
    raise SystemExit(f"input contract mismatch: {body}")
if response.get("data", {}).get("stepName") != "worker_callback_smoke":
    raise SystemExit(f"response parse mismatch: {response}")

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "request": {
        "method": captured["method"],
        "path": captured["path"],
        "tokenHeaderPresent": captured["token"] == "callback-smoke-token",
        "stepName": body["step_name"],
        "status": body["status"],
    },
    "response": response["data"],
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker 回写 client 验证通过。")
print(f"Step: {body['step_name']} -> {response['data']['status']}")
print(f"证据文件: {summary_path}")
PY
