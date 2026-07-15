#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-tool-smoke"
SUMMARY_JSON="$ARTIFACT_DIR/last-run.json"

usage() {
  cat <<'EOF'
RepoPilot Agent Worker 工具 client smoke

用法:
  ./scripts/agent-worker-tool-smoke.sh

说明:
  - 启动本地 HTTP stub，验证 Python Agent Worker 的 BackendApiClient 工具读取能力。
  - 校验 /context、/project/files、/project/file、/project/search 和 /project/symbols 路径。
  - 校验 callback token header 和 query 参数。
  - 运行证据写入 output/agent-worker-tool-smoke/last-run.json。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

mkdir -p "$ARTIFACT_DIR"

echo "RepoPilot Agent Worker 工具 client smoke"

PYTHONPATH="$ROOT_DIR/agent-worker" python3 - "$SUMMARY_JSON" <<'PY'
import json
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from app.clients.backend_api import BackendApiClient

summary_path = Path(sys.argv[1])
captured = []


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        event = {
            "method": self.command,
            "path": parsed.path,
            "query": query,
            "token": self.headers.get("X-RepoPilot-Worker-Token"),
        }
        captured.append(event)
        if parsed.path.endswith("/context"):
            data = {
                "runId": 505,
                "runStatus": "RUNNING",
                "taskId": 303,
                "taskStatus": "CREATED",
                "taskType": "FEATURE",
                "title": "给 User 模块新增分页查询接口",
                "description": "读取 Controller 并检索 User 代码。",
                "projectId": 202,
                "repoUrl": "file:///demo",
                "repoFullName": "demo/repo",
                "defaultBranch": "main",
                "localPath": "/workspace/repos/202/source",
                "projectStatus": "READY",
            }
        elif parsed.path.endswith("/project/files"):
            data = [
                {"path": "src/main/java/com/example/UserController.java", "type": "FILE", "size": 1200},
                {"path": "src/test/java/com/example/UserServiceTest.java", "type": "FILE", "size": 900},
            ]
        elif parsed.path.endswith("/project/file"):
            data = {
                "path": query.get("path", [""])[0],
                "content": "public class UserController {}",
                "size": 30,
            }
        elif parsed.path.endswith("/project/search"):
            data = {
                "query": query.get("query", [""])[0],
                "limit": int(query.get("limit", ["8"])[0]),
                "results": [
                    {
                        "chunkId": 77,
                        "filePath": "src/main/java/com/example/UserController.java",
                        "chunkType": "SYMBOL",
                        "symbolType": "CONTROLLER",
                        "symbolName": "UserController",
                        "qualifiedName": "com.example.UserController",
                        "startLine": 1,
                        "endLine": 20,
                        "summary": "User Controller",
                        "preview": "class UserController",
                    }
                ],
            }
        elif parsed.path.endswith("/project/symbols"):
            data = [
                {
                    "id": 12,
                    "filePath": "src/main/java/com/example/UserController.java",
                    "symbolType": query.get("type", ["CONTROLLER"])[0],
                    "name": "UserController",
                    "qualifiedName": "com.example.UserController",
                    "annotations": "RestController",
                    "startLine": 1,
                    "endLine": 20,
                }
            ]
        else:
            self.send_response(404)
            self.end_headers()
            return
        response = {
            "success": True,
            "data": data,
            "code": None,
            "message": None,
            "traceId": "tool-smoke",
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
        callback_token="tool-smoke-token",
        timeout_seconds=3,
    )
    context = client.load_run_context(505)
    files = client.list_project_files(505, max_depth=3)
    file_content = client.read_project_file(505, "src/main/java/com/example/UserController.java")
    search = client.search_code(505, "User Controller", limit=4)
    symbols = client.list_symbols(505, "CONTROLLER")
finally:
    server.shutdown()
    thread.join(timeout=3)

expected_paths = [
    "/api/internal/agent-worker/runs/505/context",
    "/api/internal/agent-worker/runs/505/project/files",
    "/api/internal/agent-worker/runs/505/project/file",
    "/api/internal/agent-worker/runs/505/project/search",
    "/api/internal/agent-worker/runs/505/project/symbols",
]
actual_paths = [event["path"] for event in captured]
if actual_paths != expected_paths:
    raise SystemExit(f"tool client path mismatch: {actual_paths}")
if any(event["method"] != "GET" for event in captured):
    raise SystemExit(f"tool client method mismatch: {captured}")
if any(event["token"] != "tool-smoke-token" for event in captured):
    raise SystemExit("tool client token header mismatch")
if captured[1]["query"].get("maxDepth") != ["3"]:
    raise SystemExit(f"files query mismatch: {captured[1]}")
if captured[2]["query"].get("path") != ["src/main/java/com/example/UserController.java"]:
    raise SystemExit(f"read_file query mismatch: {captured[2]}")
if captured[3]["query"].get("query") != ["User Controller"] or captured[3]["query"].get("limit") != ["4"]:
    raise SystemExit(f"search query mismatch: {captured[3]}")
if captured[4]["query"].get("type") != ["CONTROLLER"]:
    raise SystemExit(f"symbols query mismatch: {captured[4]}")
if context.get("repoFullName") != "demo/repo":
    raise SystemExit(f"context parse mismatch: {context}")
if files[0].get("path") != "src/main/java/com/example/UserController.java":
    raise SystemExit(f"files parse mismatch: {files}")
if "UserController" not in file_content.get("content", ""):
    raise SystemExit(f"read_file parse mismatch: {file_content}")
if search.get("results", [{}])[0].get("chunkId") != 77:
    raise SystemExit(f"search parse mismatch: {search}")
if symbols[0].get("symbolType") != "CONTROLLER":
    raise SystemExit(f"symbols parse mismatch: {symbols}")

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "requests": [
        {
            "method": event["method"],
            "path": event["path"],
            "query": event["query"],
            "tokenHeaderPresent": event["token"] == "tool-smoke-token",
        }
        for event in captured
    ],
    "context": {
        "runId": context["runId"],
        "taskId": context["taskId"],
        "projectId": context["projectId"],
        "repoFullName": context["repoFullName"],
    },
    "filesCount": len(files),
    "readFile": {
        "path": file_content["path"],
        "size": file_content["size"],
    },
    "search": {
        "query": search["query"],
        "limit": search["limit"],
        "resultCount": len(search["results"]),
    },
    "symbolsCount": len(symbols),
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker 工具 client 验证通过。")
print(f"Context: run {context['runId']} / task {context['taskId']} / {context['repoFullName']}")
print(f"Files: {len(files)}; Search results: {len(search['results'])}; Symbols: {len(symbols)}")
print(f"证据文件: {summary_path}")
PY
