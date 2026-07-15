#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/agent-worker-smoke/logs"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-smoke"
WORKER_PORT="${AGENT_WORKER_PORT:-8090}"
WORKER_URL="${REPOPILOT_AGENT_WORKER_URL:-http://127.0.0.1:${WORKER_PORT}}"
HEALTH_JSON="$ARTIFACT_DIR/health.json"
START_JSON="$ARTIFACT_DIR/start-run.json"
SUMMARY_JSON="$ARTIFACT_DIR/last-run.json"

worker_pid=""

usage() {
  cat <<'EOF'
RepoPilot Agent Worker 契约 smoke

用法:
  ./scripts/agent-worker-smoke.sh

说明:
  - 若 REPOPILOT_AGENT_WORKER_URL 已有 worker，本脚本会复用。
  - 若没有 worker，本脚本会临时启动 agent-worker FastAPI 服务。
  - 验证 /health 和 /runs/{run_id}/start 响应契约。
  - 运行证据写入 output/agent-worker-smoke/last-run.json。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

cleanup() {
  if [ -n "$worker_pid" ]; then
    kill "$worker_pid" >/dev/null 2>&1 || true
    wait "$worker_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require_python_dependencies() {
  python3 - <<'PY'
missing = []
for name in ("fastapi", "uvicorn", "pydantic", "pydantic_settings"):
    try:
        __import__(name)
    except Exception as exc:
        missing.append(f"{name}: {exc}")
if missing:
    print("Agent Worker 依赖缺失：")
    for item in missing:
        print(f"- {item}")
    print("可在 agent-worker 目录安装 pyproject.toml 中的依赖后重试。")
    raise SystemExit(2)
PY
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local attempts="${3:-60}"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "等待 $label 超时：$url" >&2
  return 1
}

mkdir -p "$LOG_DIR" "$ARTIFACT_DIR"

echo "RepoPilot Agent Worker 契约 smoke"
echo "Worker: $WORKER_URL"

require_python_dependencies

if curl -fsS "$WORKER_URL/health" >/dev/null 2>&1; then
  echo "使用已有 Agent Worker：$WORKER_URL"
else
  echo "启动临时 Agent Worker..."
  (
    cd "$ROOT_DIR/agent-worker"
    PYTHONPATH="$ROOT_DIR/agent-worker" \
      REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN="" \
      BACKEND_CALLBACK_TOKEN="" \
      python3 -m uvicorn app.main:app --host 127.0.0.1 --port "$WORKER_PORT"
  ) >"$LOG_DIR/agent-worker.log" 2>&1 &
  worker_pid="$!"
  wait_for_url "$WORKER_URL/health" "agent-worker" 60
fi

curl -fsS "$WORKER_URL/health" -o "$HEALTH_JSON"
curl -fsS \
  -H "Content-Type: application/json" \
  -d '{
    "task_id": 101,
    "project_id": 202,
    "user_request": "给 User 模块新增分页查询接口",
    "repo_path": "/workspace/repos/202/source",
    "base_branch": "main"
  }' \
  "$WORKER_URL/runs/303/start" \
  -o "$START_JSON"

python3 - "$HEALTH_JSON" "$START_JSON" "$SUMMARY_JSON" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

health_path = Path(sys.argv[1])
start_path = Path(sys.argv[2])
summary_path = Path(sys.argv[3])

health = json.loads(health_path.read_text(encoding="utf-8"))
start = json.loads(start_path.read_text(encoding="utf-8"))

expected_nodes = [
    "load_task_context",
    "ensure_index",
    "plan_task",
    "retrieve_context",
    "generate_patch",
    "apply_patch",
    "run_tests",
    "review_patch",
    "wait_approval",
    "create_pr",
]

if health.get("status") != "UP" or health.get("service") != "agent-worker":
    raise SystemExit(f"health contract mismatch: {health}")

if start.get("run_id") != 303:
    raise SystemExit(f"start response run_id mismatch: {start}")
if start.get("accepted") is not True:
    raise SystemExit(f"start response accepted mismatch: {start}")
if start.get("status") != "QUEUED":
    raise SystemExit(f"start response status mismatch: {start}")
if start.get("graph_nodes") != expected_nodes:
    raise SystemExit(f"graph nodes mismatch: {start.get('graph_nodes')}")

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "health": health,
    "startRun": start,
    "expectedGraphNodes": expected_nodes,
}
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Agent Worker 契约验证通过。")
print(f"Graph nodes: {len(expected_nodes)}")
print(f"证据文件: {summary_path}")
PY
